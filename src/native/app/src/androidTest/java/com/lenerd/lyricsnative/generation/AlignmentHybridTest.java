package com.lenerd.lyricsnative.generation;

import ai.onnxruntime.*;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lenerd.lyricsnative.sing.PreparationSpeedTest;
import com.lenerd.lyricsnative.sing.QnnRuntime;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AlignmentHybridTest {
    private static final String FEATURES = "/wav2vec2/encoder/layer_norm/Add_1_output_0";
    private static final int FRAMES = 249, WIDTH = 768;

    private static File staged(String name) throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "hybrid-" + name);
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat /data/local/tmp/lyrics-hybrid-" + name);
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = Files.newOutputStream(file.toPath())) {
            input.transferTo(output);
        }
        assertTrue(name, file.length() > 1000);
        return file;
    }

    private static OrtSession.SessionOptions options() throws Exception {
        var options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(6);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        return options;
    }

    private static float[] normalize(float[] audio) {
        double mean = 0, variance = 0;
        for (float sample : audio) mean += sample;
        mean /= audio.length;
        for (float sample : audio) variance += (sample - mean) * (sample - mean);
        variance /= audio.length;
        float scale = (float) (1 / Math.sqrt(variance + 1e-7));
        float[] normalized = new float[audio.length];
        for (int i = 0; i < audio.length; i++) normalized[i] = (float) ((audio[i] - mean) * scale);
        return normalized;
    }

    private static float[][] logProbabilities(float[] logits, int frames) {
        float[][] output = new float[frames][CtcAligner.CLASSES];
        for (int t = 0; t < frames; t++) {
            float max = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < 32; c++) max = Math.max(max, logits[t * 32 + c]);
            double sum = 0;
            for (int c = 0; c < 32; c++) sum += Math.exp(logits[t * 32 + c] - max);
            float log = (float) (max + Math.log(sum));
            for (int c = 0; c < 32; c++) {
                output[t][c] = logits[t * 32 + c] - log;
                assertTrue("Nonfinite emission", Float.isFinite(output[t][c]));
            }
            output[t][CtcAligner.STAR] = -20;
        }
        return output;
    }

    private static float[] values(OrtSession.Result result) {
        FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    @Test
    public void variableClipsMatchCpu() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("hybrid", "0").equals("1"));
        var environment = OrtEnvironment.getEnvironment();
        boolean masked = InstrumentationRegistry.getArguments().getString("masked", "0").equals("1");
        boolean external = InstrumentationRegistry.getArguments().getString("external", "0").equals("1");
        if (external) staged("alignment-378348ee.fp16.onnx");
        File runtime = PreparationSpeedTest.runtime();
        File frontendModel = staged("frontend.onnx"), bodyModel = staged(masked ? "masked.onnx" : "body.onnx"), referenceModel = staged("reference.onnx"), currentModel = staged("int8.onnx");
        try (var frontOptions = options(); var referenceOptions = options(); var bodyOptions = options()) {
            bodyOptions.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            QnnRuntime.configure(bodyOptions, runtime, "htp", "burst");
            File cache = new File(bodyModel.getParentFile(), "hybrid-body-ctx.onnx");
            boolean cached = InstrumentationRegistry.getArguments().getString("cached", "0").equals("1");
            if (!cached) bodyOptions.addConfigEntry("ep.context_enable", "1");
            bodyOptions.addConfigEntry("ep.context_embed_mode", "0");
            bodyOptions.addConfigEntry("ep.context_file_path", cache.getAbsolutePath());
            bodyOptions.enableProfiling(new File(bodyModel.getParentFile(), "hybrid-profile").getAbsolutePath());
            long start = System.nanoTime();
            try (var frontend = environment.createSession(frontendModel.getAbsolutePath(), frontOptions); var reference = environment.createSession(referenceModel.getAbsolutePath(), referenceOptions); var current = new AlignmentModel(currentModel); var body = environment.createSession((cached ? cache : bodyModel).getAbsolutePath(), bodyOptions)) {
                Log.i("AlignmentHybrid", "SETUP ms=" + (System.nanoTime() - start) / 1e6);
                for (int recording = 1; recording <= 4; recording++) {
                    var bytes = ByteBuffer.wrap(Files.readAllBytes(staged("speech" + recording + ".f32").toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                    float[] source = new float[bytes.remaining()];
                    bytes.get(source);
                    for (int clip = 0; clip < 4; clip++) {
                        int length = new int[] {38400, 52800, 72000, 80000}[clip];
                        int offset = Math.min(clip * 16000, Math.max(0, source.length - length));
                        length = Math.min(length, source.length - offset);
                        float[] audio = Arrays.copyOfRange(source, offset, offset + length);
                        float[] normalized = normalize(audio);
                        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalized), new long[] {1, length})) {
                            float[][] expected;
                            try (var output = reference.run(Map.of("input_values", input))) {
                                float[] raw = values(output);
                                expected = logProbabilities(raw, raw.length / 32);
                            }
                            start = System.nanoTime();
                            float[] embeddings;
                            int frames;
                            if (masked) {
                                frames = (length - 400) / 320 + 1;
                                embeddings = Arrays.copyOf(normalized, 80000);
                            } else {
                                try (var output = frontend.run(Map.of("input_values", input))) {
                                    var tensor = (OnnxTensor) output.get(0);
                                    frames = (int) tensor.getInfo().getShape()[1];
                                    assertTrue(frames <= FRAMES);
                                    embeddings = Arrays.copyOf(values(output), FRAMES * WIDTH);
                                }
                            }
                            float[] mask = new float[FRAMES];
                            if (masked) Arrays.fill(mask, 0, frames, 1);
                            else Arrays.fill(mask, frames, FRAMES, -10000);
                            float[] convolutionMask = new float[15999];
                            Arrays.fill(convolutionMask, 0, (length - 10) / 5 + 1, 1);
                            float[][] actual;
                            try (var features = OnnxTensor.createTensor(environment, FloatBuffer.wrap(embeddings), masked ? new long[] {1, 80000} : new long[] {1, FRAMES, WIDTH}); var padding = OnnxTensor.createTensor(environment, FloatBuffer.wrap(mask), masked ? new long[] {1, FRAMES, 1} : new long[] {1, 1, FRAMES}); var convolution = OnnxTensor.createTensor(environment, FloatBuffer.wrap(convolutionMask), new long[] {1, 1, 15999}); var run = new OrtSession.RunOptions()) {
                                run.addRunConfigEntry("qnn.htp_perf_mode", "burst");
                                run.addRunConfigEntry("qnn.htp_perf_mode_post_run", "power_saver");
                                try (var output = body.run(masked ? Map.of("input_values", features, "frame_mask", padding, "convolution_mask", convolution) : Map.of(FEATURES, features, "attention_padding_mask", padding), run)) {
                                    actual = logProbabilities(values(output), frames);
                                }
                            }
                            double hybridMs = (System.nanoTime() - start) / 1e6;
                            start = System.nanoTime();
                            float[][] production = current.emissions(audio);
                            double currentMs = (System.nanoTime() - start) / 1e6;
                            Log.i("AlignmentHybrid", "CLIP recording=" + recording + " clip=" + clip + " samples=" + length + " hybrid_ms=" + hybridMs + " current_ms=" + currentMs + " float_reference=" + GenerationSpeedTest.decoded(expected) + " hybrid=" + GenerationSpeedTest.decoded(actual));
                            var floatComparison = GenerationSpeedTest.compare("hybrid_vs_float recording=" + recording + " clip=" + clip, expected, actual);
                            var currentComparison = GenerationSpeedTest.compare("hybrid_vs_current recording=" + recording + " clip=" + clip, production, actual);
                            assertNotNull(floatComparison);
                            assertNotNull(currentComparison);
                            assertTrue("Float reference boundary shift exceeded 40 ms", floatComparison.maximumShiftMs() <= 40);
                            assertTrue("Current model boundary shift exceeded 40 ms", currentComparison.maximumShiftMs() <= 40);
                            assertEquals(0, currentComparison.medianShiftMs());
                        }
                    }
                }
                var profile = new File(body.endProfiling());
                var events = new org.json.JSONArray(new String(Files.readAllBytes(profile.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                int cpu = 0, hardware = 0;
                for (int i = 0; i < events.length(); i++) {
                    var args = events.getJSONObject(i).optJSONObject("args");
                    if (args == null) continue;
                    if (args.optString("provider").equals("CPUExecutionProvider")) cpu++;
                    if (args.optString("provider").equals("QNNExecutionProvider")) hardware++;
                }
                Log.i("AlignmentHybrid", "PROFILE hardware=" + hardware + " cpu=" + cpu);
                assertTrue(hardware > 0);
                assertEquals(0, cpu);
            }
        }
    }
}
