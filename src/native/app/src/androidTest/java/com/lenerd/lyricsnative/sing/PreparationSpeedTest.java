package com.lenerd.lyricsnative.sing;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.nio.FloatBuffer;
import java.util.Map;
import ai.onnxruntime.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreparationSpeedTest {
    private static File cachedRuntime;

    public static synchronized File runtime() throws Exception {
        if (cachedRuntime != null) return cachedRuntime;
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        cachedRuntime = QnnRuntime.install(instrumentation.getTargetContext(), path -> {
            try (var input = instrumentation.getContext().getAssets().open(path)) {
                return input.readAllBytes();
            }
        });
        return cachedRuntime;
    }

    public static VocalSeparator separator() throws Exception {
        return new VocalSeparator(VocalSeparator.bundledModel(), 1.03f, runtime(), "burst");
    }

    public static VocalSeparator separator(boolean nativeFft, boolean holdBurst) throws Exception {
        return new VocalSeparator(VocalSeparator.bundledModel(), 1.03f, runtime(), "burst", nativeFft, holdBurst ? "burst" : "power_saver");
    }

    @Test
    public void spectralParityAndSpeed() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("speed", "0").equals("1"));
        var reference = new SpectralTransform();
        var candidate = new BufferedSpectralTransform();
        float[][] audio = new float[2][SpectralTransform.SAMPLES];
        var random = new Random(19);
        for (int trial = 0; trial < 4; trial++) {
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < audio[c].length; i++) audio[c][i] = trial == 0 ? 0 : trial == 1 ? (i == 1200 ? 1 : 0) : (float) (0.1 * random.nextGaussian());
            }
            float[] expected = reference.forward(audio), actual = candidate.forward(audio);
            assertArrayEquals(expected, actual, 0);
            float[][] expectedPcm = reference.inverse(expected), actualPcm = candidate.inverse(actual);
            for (int c = 0; c < 2; c++) assertArrayEquals(expectedPcm[c], actualPcm[c], 0);
        }
        for (int repeat = 0; repeat < 4; repeat++) {
            double[] oldTimes = new double[15], newTimes = new double[15];
            for (int i = -3; i < oldTimes.length; i++) {
                boolean oldFirst = (i & 1) == 0;
                for (int order = 0; order < 2; order++) {
                    boolean old = order == 0 ? oldFirst : !oldFirst;
                    long start = System.nanoTime();
                    float[][] output = old ? reference.inverse(reference.forward(audio)) : candidate.inverse(candidate.forward(audio));
                    assertTrue(Float.isFinite(output[0][1000]));
                    if (i >= 0) (old ? oldTimes : newTimes)[i] = (System.nanoTime() - start) / 1e6;
                }
            }
            Arrays.sort(oldTimes);
            Arrays.sort(newTimes);
            Log.i("LyricsSpeed", "SPECTRAL repeat=" + repeat + " reference_ms=" + oldTimes[7] + " buffered_ms=" + newTimes[7] + " bit_exact=true");
        }
    }

    @Test
    public void vocalSessionReuse() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("speed", "0").equals("1"));
        float[][] audio = new float[2][SpectralTransform.SAMPLES];
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < audio[c].length; i++) audio[c][i] = (float) (0.1 * Math.sin(i * 2 * Math.PI * (c == 0 ? 220 : 440) / 44100));
        }
        float[][] expected = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long start = System.nanoTime();
            try (var separator = separator()) {
                double setup = (System.nanoTime() - start) / 1e6;
                assertTrue(separator.backend().contains("NPU"));
                for (int run = 0; run < 3; run++) {
                    start = System.nanoTime();
                    float[][] output = separator.separate(audio);
                    double elapsed = (System.nanoTime() - start) / 1e6;
                    if (expected == null) expected = output;
                    for (int c = 0; c < 2; c++) assertArrayEquals(expected[c], output[c], 1e-5f);
                    Log.i("LyricsSpeed", "VOCAL_SESSION attempt=" + attempt + " setup_ms=" + setup + " run=" + run + " inference_ms=" + elapsed);
                }
            }
        }
    }

    @Test
    public void vocalCompiledCache() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("speed", "0").equals("1"));
        var environment = OrtEnvironment.getEnvironment();
        File runtime = runtime();
        File cache = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "speed-vocal-ctx.onnx");
        java.nio.file.Files.deleteIfExists(cache.toPath());
        float[][] audio = new float[2][SpectralTransform.SAMPLES];
        var random = new Random(81);
        for (float[] channel : audio) {
            for (int i = 0; i < channel.length; i++) channel[i] = (float) (0.1 * random.nextGaussian());
        }
        float[] features = new SpectralTransform().forward(audio), expected = null;
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), new long[] {1, 4, SpectralTransform.BINS, SpectralTransform.TIMES})) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var options = new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(4);
                    options.setInterOpNumThreads(1);
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    options.addConfigEntry("session.intra_op.allow_spinning", "0");
                    VocalSeparator.configureQnn(options, runtime);
                    options.addConfigEntry("ep.context_file_path", cache.getAbsolutePath());
                    if (attempt == 0) {
                        options.addConfigEntry("ep.context_enable", "1");
                        options.addConfigEntry("ep.context_embed_mode", "0");
                    }
                    options.enableProfiling(new File(cache.getParentFile(), "speed-vocal-profile").getAbsolutePath());
                    long start = System.nanoTime();
                    try (var session = attempt == 0 ? environment.createSession(VocalSeparator.bundledModel(), options) : environment.createSession(cache.getAbsolutePath(), options)) {
                        double setup = (System.nanoTime() - start) / 1e6;
                        for (int run = 0; run < 3; run++) {
                            start = System.nanoTime();
                            try (var output = session.run(Map.of("input", input))) {
                                FloatBuffer buffer = ((OnnxTensor) output.get(0)).getFloatBuffer();
                                float[] actual = new float[buffer.remaining()];
                                buffer.get(actual);
                                for (float value : actual) assertTrue(Float.isFinite(value));
                                if (expected == null) expected = actual;
                                assertArrayEquals(expected, actual, 0);
                            }
                            Log.i("LyricsSpeed", "VOCAL_CACHE attempt=" + attempt + " setup_ms=" + setup + " run=" + run + " inference_ms=" + (System.nanoTime() - start) / 1e6 + " bit_exact=true");
                        }
                        var profile = new File(session.endProfiling());
                        var events = new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(profile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                        int hardware = 0, cpu = 0;
                        for (int i = 0; i < events.length(); i++) {
                            var args = events.getJSONObject(i).optJSONObject("args");
                            if (args == null) continue;
                            if (args.optString("provider").equals("QNNExecutionProvider")) hardware++;
                            if (args.optString("provider").equals("CPUExecutionProvider")) cpu++;
                        }
                        assertTrue(hardware > 0);
                        assertEquals(0, cpu);
                        Log.i("LyricsSpeed", "VOCAL_CACHE_PROFILE attempt=" + attempt + " hardware=" + hardware + " cpu=" + cpu);
                        java.nio.file.Files.deleteIfExists(profile.toPath());
                    }
                }
            }
        }
    }
}
