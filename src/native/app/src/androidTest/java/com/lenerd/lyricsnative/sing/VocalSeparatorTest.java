package com.lenerd.lyricsnative.sing;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VocalSeparatorTest {
    private static java.io.File runtime;

    private static synchronized java.io.File runtime() throws Exception {
        if (runtime != null) return runtime;
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        runtime = QnnRuntime.install(instrumentation.getTargetContext(), path -> {
            try (var input = instrumentation.getContext().getAssets().open(path); var bytes = new java.io.ByteArrayOutputStream()) {
                byte[] block = new byte[65536];
                for (int count; (count = input.read(block)) != -1;) bytes.write(block, 0, count);
                return bytes.toByteArray();
            }
        });
        return runtime;
    }

    @Test
    public void missingQnnRuntimeFallsBackToCpu() throws Exception {
        var directory = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "missing-qnn-" + java.util.UUID.randomUUID());
        try (var separator = new VocalSeparator(VocalSeparator.bundledModel(), 1.03f, directory)) {
            assertEquals("CPU", separator.backend());
            float[][] silence = separator.separate(new float[2][SpectralTransform.SAMPLES]);
            for (float[] channel : silence) {
                for (float sample : channel) assertTrue(Float.isFinite(sample));
            }
        }
    }

    @Test
    public void generationAndSingCanUseNpuTogether() throws Exception {
        byte[] model = VocalSeparator.bundledModel();
        float[][] tone = new float[2][SpectralTransform.SAMPLES];
        for (int i = 0; i < tone[0].length; i++) tone[0][i] = tone[1][i] = (float) (0.1 * Math.sin(i * 2 * Math.PI * 440 / 44100));
        try (var playback = new VocalSeparator(model, 1.03f, runtime(), "power_saver"); var generation = new VocalSeparator(model, 1.03f, runtime(), "burst", false, "power_saver")) {
            assertTrue(playback.backend().contains("NPU"));
            assertTrue(generation.backend().contains("NPU"));
            float[][] expected = playback.separate(tone);
            float[][] actual = generation.separate(tone);
            for (int channel = 0; channel < 2; channel++) assertArrayEquals(expected[channel], actual[channel], 1e-5f);
        }
    }

    @Test
    public void corruptedRuntimeAssetIsRejected() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        try {
            QnnRuntime.install(instrumentation.getTargetContext(), path -> {
                try (var input = instrumentation.getContext().getAssets().open(path); var bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] block = new byte[65536];
                    for (int count; (count = input.read(block)) != -1;) bytes.write(block, 0, count);
                    byte[] data = bytes.toByteArray();
                    if (path.equals("runtime.json")) {
                        var manifest = new org.json.JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8));
                        manifest.getJSONArray("libraries").getJSONObject(0).put("sha256", "0".repeat(64));
                        return manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    return data;
                }
            });
            fail("Accepted a corrupted library checksum");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }

    @Test
    public void profileHardwareAssignment() throws Exception {
        var environment = OrtEnvironment.getEnvironment();
        assertTrue("QNN execution provider missing", OrtEnvironment.getAvailableProviders().contains(OrtProvider.QNN));
        String prefix = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "sing-backend").getAbsolutePath();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            VocalSeparator.configureQnn(options, runtime());
            options.enableProfiling(prefix);
            try (var session = environment.createSession(VocalSeparator.bundledModel(), options);
                var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(new float[4 * SpectralTransform.BINS * SpectralTransform.TIMES]), new long[] {1, 4, SpectralTransform.BINS, SpectralTransform.TIMES})) {
                try (var result = session.run(Map.of("input", input))) {
                    assertNotNull(result.get(0));
                }
                Path path = java.nio.file.Paths.get(session.endProfiling());
                var events = new JSONArray(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
                int cpu = 0, hardware = 0;
                for (int i = 0; i < events.length(); i++) {
                    var args = events.getJSONObject(i).optJSONObject("args");
                    if (args == null) continue;
                    String provider = args.optString("provider");
                    if (provider.equals("CPUExecutionProvider")) cpu++;
                    if (provider.equals("QNNExecutionProvider")) hardware++;
                }
                assertTrue("No model operations executed on the NPU", hardware > 0);
                assertEquals("Unexpected CPU model operations", 0, cpu);
                Log.i("SingBackendTest", "PROFILE hardware_nodes=" + hardware + " cpu_nodes=" + cpu);
                Files.delete(path);
            }
        }
    }

    @Test
    public void hardwareOutputMatchesCpu() throws Exception {
        byte[] model = VocalSeparator.bundledModel();
        float[][] tone = new float[2][SpectralTransform.SAMPLES];
        for (int i = 0; i < tone[0].length; i++) {
            tone[0][i] = (float) (0.1 * Math.sin(i * 2 * Math.PI * 440 / 44100));
            tone[1][i] = (float) (0.1 * Math.sin(i * 2 * Math.PI * 880 / 44100));
        }
        float[][] reference;
        try (var cpu = new VocalSeparator(model, 1.03f, null)) {
            cpu.separate(tone);
            long start = System.nanoTime();
            reference = cpu.separate(tone);
            Log.i("SingBackendTest", "CPU warm_ms=" + (System.nanoTime() - start) / 1e6);
        }
        for (String performance : new String[] {"power_saver", "burst"}) {
            try (var accelerated = new VocalSeparator(model, 1.03f, runtime(), performance)) {
                accelerated.separate(tone);
                long start = System.nanoTime();
                float[][] actual = accelerated.separate(tone);
                Log.i("SingBackendTest", accelerated.backend() + " performance=" + performance + " warm_ms=" + (System.nanoTime() - start) / 1e6);
                assertTrue("NPU initialization fell back to CPU", accelerated.backend().contains("NPU"));
                double error = 0, energy = 0, inputEnergy = 0, peakError = 0;
                for (int channel = 0; channel < 2; channel++) {
                    assertEquals(reference[channel].length, actual[channel].length);
                    for (int i = 0; i < actual[channel].length; i++) {
                        assertTrue(Float.isFinite(actual[channel][i]));
                        double difference = reference[channel][i] - actual[channel][i];
                        error += difference * difference;
                        energy += reference[channel][i] * reference[channel][i];
                        inputEnergy += tone[channel][i] * tone[channel][i];
                        peakError = Math.max(peakError, Math.abs(difference));
                    }
                }
                double relativeError = Math.sqrt(error / Math.max(inputEnergy, 1e-12));
                Log.i("SingBackendTest", "OUTPUT error_rms=" + Math.sqrt(error / (2 * SpectralTransform.SAMPLES)) + " relative_to_input=" + relativeError + " relative_to_vocals=" + Math.sqrt(error / Math.max(energy, 1e-12)) + " peak_error=" + peakError);
                assertTrue("FP16 error exceeds 1% of input RMS: " + relativeError, relativeError < 0.01);
                assertTrue("FP16 sample error exceeds 0.01: " + peakError, peakError < 0.01);
            }
        }
    }
}
