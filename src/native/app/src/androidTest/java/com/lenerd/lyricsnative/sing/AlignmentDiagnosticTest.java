package com.lenerd.lyricsnative.sing;

import ai.onnxruntime.*;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AlignmentDiagnosticTest {
    private static File staged(String remote, String name) throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), name);
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat /data/local/tmp/" + remote);
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = Files.newOutputStream(file.toPath())) {
            input.transferTo(output);
        }
        assertTrue("Missing diagnostic fixture: " + remote, file.length() > 1000);
        return file;
    }

    private static float[] normalizedSpeech(int offset) throws Exception {
        File file = staged("lyrics-speed-speech.f32", "diagnostic-speech.f32");
        var buffer = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] audio = new float[80000];
        buffer.position(offset);
        buffer.get(audio);
        double mean = 0, variance = 0;
        for (float sample : audio) mean += sample;
        mean /= audio.length;
        for (float sample : audio) variance += (sample - mean) * (sample - mean);
        variance /= audio.length;
        float scale = (float) (1 / Math.sqrt(variance + 1e-7));
        for (int i = 0; i < audio.length; i++) audio[i] = (float) ((audio[i] - mean) * scale);
        return audio;
    }

    private static OrtSession.SessionOptions options(File runtime, String backend) throws Exception {
        var options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(6);
        options.setInterOpNumThreads(1);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.addConfigEntry("optimization.disable_specified_optimizers", "SimplifiedLayerNormFusion;SkipSimplifiedLayerNormFusion");
        if (InstrumentationRegistry.getArguments().getString("nofusion", "0").equals("1")) options.addConfigEntry("optimization.disable_specified_optimizers", "SimplifiedLayerNormFusion;SkipSimplifiedLayerNormFusion;LayerNormFusion;SkipLayerNormFusion");
        if (runtime != null) {
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            QnnRuntime.configure(options, runtime, backend, "burst");
        }
        return options;
    }

    private static Map<String, float[]> run(OrtSession session, OnnxTensor input) throws Exception {
        var outputs = new LinkedHashMap<String, float[]>();
        try (var options = new OrtSession.RunOptions()) {
            options.addRunConfigEntry("qnn.htp_perf_mode", "burst");
            options.addRunConfigEntry("qnn.htp_perf_mode_post_run", "power_saver");
            long start = System.nanoTime();
            try (var result = session.run(Map.of("input_values", input), options)) {
                for (var item : result) {
                    FloatBuffer data = ((OnnxTensor) item.getValue()).getFloatBuffer();
                    float[] values = new float[data.remaining()];
                    data.get(values);
                    outputs.put(item.getKey(), values);
                }
            }
            Log.i("AlignmentDiagnostic", "INFERENCE ms=" + (System.nanoTime() - start) / 1e6);
        }
        return outputs;
    }

    private static void compare(String name, float[] reference, float[] actual) {
        assertEquals(name, reference.length, actual.length);
        double error = 0, energy = 0, actualEnergy = 0, product = 0, peak = 0;
        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        int nonfinite = 0;
        for (int i = 0; i < actual.length; i++) {
            if (!Float.isFinite(actual[i])) nonfinite++;
            double difference = reference[i] - actual[i];
            error += difference * difference;
            energy += reference[i] * reference[i];
            actualEnergy += actual[i] * actual[i];
            product += reference[i] * actual[i];
            peak = Math.max(peak, Math.abs(difference));
            min = Math.min(min, actual[i]);
            max = Math.max(max, actual[i]);
        }
        Log.i("AlignmentDiagnostic", name + " relative_l2=" + Math.sqrt(error / Math.max(energy, 1e-30)) + " cosine=" + product / Math.sqrt(Math.max(energy * actualEnergy, 1e-30)) + " peak=" + peak + " min=" + min + " max=" + max + " nonfinite=" + nonfinite);
        if (name.endsWith("diagnostic_0")) {
            Log.i("AlignmentDiagnostic", name + " expected_head=" + java.util.Arrays.toString(java.util.Arrays.copyOf(reference, 16)) + " actual_head=" + java.util.Arrays.toString(java.util.Arrays.copyOf(actual, 16)));
            int bestLag = 0;
            double bestError = Double.POSITIVE_INFINITY;
            for (int lag = -8; lag <= 8; lag++) {
                double lagError = 0;
                for (int i = 8; i < actual.length - 8; i++) lagError += Math.pow(reference[i] - actual[i + lag], 2);
                if (lagError < bestError) { bestError = lagError; bestLag = lag; }
            }
            Log.i("AlignmentDiagnostic", name + " best_lag=" + bestLag + " lag_relative_l2=" + Math.sqrt(bestError / energy));
        }
        if (name.endsWith("logits")) {
            int equal = 0, blank = 0;
            StringBuilder text = new StringBuilder();
            int previous = -1;
            for (int t = 0; t < actual.length / 32; t++) {
                int expectedBest = 0, actualBest = 0;
                for (int c = 1; c < 32; c++) {
                    if (reference[t * 32 + c] > reference[t * 32 + expectedBest]) expectedBest = c;
                    if (actual[t * 32 + c] > actual[t * 32 + actualBest]) actualBest = c;
                }
                if (expectedBest == actualBest) equal++;
                if (actualBest == 0) blank++;
                if (actualBest > 0 && actualBest != previous) text.append("???? ETAONIHSRDLUMWCFGYPBVK'XJQZ".charAt(actualBest));
                previous = actualBest;
            }
            Log.i("AlignmentDiagnostic", name + " frame_agreement=" + equal + "/" + actual.length / 32 + " blank=" + blank + " text=" + text);
        }
    }

    @Test
    public void compareLayers() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("diagnostic", "0").equals("1"));
        String backend = InstrumentationRegistry.getArguments().getString("backend", "htp");
        File runtime = PreparationSpeedTest.runtime();
        var environment = OrtEnvironment.getEnvironment();
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalizedSpeech(0)), new long[] {1, 80000})) {
            for (String variant : InstrumentationRegistry.getArguments().getString("variants", "baseline,fp32,conv2d").split(",")) {
                File model = staged("lyrics-diagnostic-" + variant + ".onnx", "diagnostic-" + variant + ".onnx");
                Map<String, float[]> reference;
                try (var options = options(null, backend); var session = environment.createSession(model.getAbsolutePath(), options)) {
                    reference = run(session, input);
                }
                try (var options = options(runtime, backend)) {
                    options.enableProfiling(new File(model.getParentFile(), "diagnostic-profile-" + variant).getAbsolutePath());
                    long start = System.nanoTime();
                    try (var session = environment.createSession(model.getAbsolutePath(), options)) {
                        Log.i("AlignmentDiagnostic", "SETUP backend=" + backend + " variant=" + variant + " ms=" + (System.nanoTime() - start) / 1e6);
                        var actual = run(session, input);
                        for (String name : reference.keySet()) compare(backend + " " + variant + " " + name, reference.get(name), actual.get(name));
                        var profile = new File(session.endProfiling());
                        var events = new org.json.JSONArray(new String(Files.readAllBytes(profile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                        int hardware = 0, cpu = 0;
                        for (int i = 0; i < events.length(); i++) {
                            var args = events.getJSONObject(i).optJSONObject("args");
                            if (args == null) continue;
                            if (args.optString("provider").equals("QNNExecutionProvider")) hardware++;
                            if (args.optString("provider").equals("CPUExecutionProvider")) cpu++;
                        }
                        Log.i("AlignmentDiagnostic", "PROFILE " + variant + " hardware=" + hardware + " cpu=" + cpu);
                        assertTrue(hardware > 0);
                        assertEquals(0, cpu);
                    }
                } catch (OrtException error) {
                    Log.e("AlignmentDiagnostic", "UNSUPPORTED backend=" + backend + " variant=" + variant, error);
                }
            }
        }
    }
}
