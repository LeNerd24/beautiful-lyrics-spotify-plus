package com.lenerd.lyricsnative.sing;

import ai.onnxruntime.*;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.Map;
import org.json.JSONArray;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AlignmentBackendTest {
    private File staged(String name, String path) throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), name);
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat " + path);
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = new FileOutputStream(file)) {
            byte[] block = new byte[65536];
            for (int count; (count = input.read(block)) != -1;) output.write(block, 0, count);
        }
        assertTrue("Staged model missing", file.length() > 100_000_000);
        return file;
    }

    @Test
    public void benchmarkCpuThreads() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().containsKey("backend"));
        File model = staged("alignment-int8.onnx", "/data/local/tmp/lyrics-alignment-int8.onnx");
        var environment = OrtEnvironment.getEnvironment();
        float[] samples = new float[80000];
        var random = new java.util.Random(1);
        for (int i = 0; i < samples.length; i++) samples[i] = (float) random.nextGaussian();
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), new long[] {1, samples.length})) {
            for (int threads : new int[] {6, 4, 8, 6, 4}) {
                try (var options = new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(threads);
                    options.setInterOpNumThreads(1);
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    options.addConfigEntry("session.intra_op.allow_spinning", "0");
                    try (var session = environment.createSession(model.getAbsolutePath(), options)) {
                        double[] times = new double[5];
                        for (int i = -1; i < times.length; i++) {
                            long start = System.nanoTime();
                            try (var result = session.run(Map.of("input_values", input))) {
                                assertNotNull(result.get(0));
                            }
                            if (i >= 0) times[i] = (System.nanoTime() - start) / 1e6;
                        }
                        java.util.Arrays.sort(times);
                        Log.i("AlignmentBackend", "CPU_THREADS=" + threads + " median_ms=" + times[times.length / 2]);
                    }
                }
            }
        }
    }

    @Test
    public void benchmark() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().containsKey("backend"));
        String backend = InstrumentationRegistry.getArguments().getString("backend", "htp");
        boolean partial = InstrumentationRegistry.getArguments().getString("partial", "0").equals("1");
        File fp16 = staged("alignment-fp16.onnx", "/data/local/tmp/lyrics-alignment-fp16.onnx");
        File int8 = staged("alignment-int8.onnx", "/data/local/tmp/lyrics-alignment-int8.onnx");
        var environment = OrtEnvironment.getEnvironment();
        float[] samples = new float[80000];
        var random = new java.util.Random(1);
        for (int i = 0; i < samples.length; i++) samples[i] = (float) (0.5 * Math.sin(i * 2 * Math.PI * 200 / 16000) + 0.05 * random.nextGaussian());
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(samples), new long[] {1, samples.length})) {
            for (File model : new File[] {int8, fp16}) {
                long start = System.nanoTime();
                try (var options = new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(4);
                    options.setInterOpNumThreads(1);
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                    if (model == fp16) options.addConfigEntry("optimization.disable_specified_optimizers", "SimplifiedLayerNormFusion;SkipSimplifiedLayerNormFusion");
                    options.addConfigEntry("session.intra_op.allow_spinning", "0");
                    try (var session = environment.createSession(model.getAbsolutePath(), options)) {
                        Log.i("AlignmentBackend", "CPU " + model.getName() + " compile_ms=" + (System.nanoTime() - start) / 1e6);
                        for (int i = 0; i < 2; i++) {
                            start = System.nanoTime();
                            try (var result = session.run(Map.of("input_values", input))) {
                                assertTrue(((OnnxTensor) result.get(0)).getFloatBuffer().remaining() > 0);
                            }
                            Log.i("AlignmentBackend", "CPU " + model.getName() + " inference_ms=" + (System.nanoTime() - start) / 1e6);
                        }
                    }
                }
            }
            var instrumentation = InstrumentationRegistry.getInstrumentation();
            File runtime = QnnRuntime.install(instrumentation.getTargetContext(), path -> {
                try (var source = instrumentation.getContext().getAssets().open(path); var bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] block = new byte[65536];
                    for (int count; (count = source.read(block)) != -1;) bytes.write(block, 0, count);
                    return bytes.toByteArray();
                }
            });
            long start = System.nanoTime();
            try (var options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(4);
                options.setInterOpNumThreads(1);
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                options.addConfigEntry("optimization.disable_specified_optimizers", "SimplifiedLayerNormFusion;SkipSimplifiedLayerNormFusion");
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO);
                if (!partial) options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
                QnnRuntime.configure(options, runtime, backend, "burst");
                options.enableProfiling(new File(instrumentation.getTargetContext().getCacheDir(), "alignment-profile").getAbsolutePath());
                try (var session = environment.createSession(fp16.getAbsolutePath(), options)) {
                    Log.i("AlignmentBackend", backend + " compile_ms=" + (System.nanoTime() - start) / 1e6);
                    for (int i = 0; i < 3; i++) {
                        start = System.nanoTime();
                        try (var result = session.run(Map.of("input_values", input))) {
                            assertTrue(((OnnxTensor) result.get(0)).getFloatBuffer().remaining() > 0);
                        }
                        Log.i("AlignmentBackend", backend + " inference_ms=" + (System.nanoTime() - start) / 1e6);
                    }
                    var path = java.nio.file.Paths.get(session.endProfiling());
                    var events = new JSONArray(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
                    int cpu = 0, hardware = 0;
                    for (int i = 0; i < events.length(); i++) {
                        var args = events.getJSONObject(i).optJSONObject("args");
                        if (args == null) continue;
                        if (args.optString("provider").equals("CPUExecutionProvider")) cpu++;
                        if (args.optString("provider").equals("QNNExecutionProvider")) hardware++;
                    }
                    Log.i("AlignmentBackend", backend + " PROFILE hardware_nodes=" + hardware + " cpu_nodes=" + cpu);
                    assertTrue("No accelerated operations", hardware > 0);
                }
            }
        }
    }
}
