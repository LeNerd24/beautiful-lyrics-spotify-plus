package com.lenerd.lyricsnative.generation;

import ai.onnxruntime.*;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lenerd.lyricsnative.sing.PreparationSpeedTest;
import com.lenerd.lyricsnative.sing.QnnRuntime;
import com.lenerd.lyricsnative.sing.VocalSeparator;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GenerationSpeedTest {
    record Comparison(int medianShiftMs, int maximumShiftMs) { }
    private static File staged(String name) throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), name);
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat /data/local/tmp/lyrics-speed-" + name);
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = Files.newOutputStream(file.toPath())) {
            input.transferTo(output);
        }
        assertTrue("Missing fixture " + name, file.length() > 1000);
        return file;
    }

    private static File model(String type) throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "alignment-" + type + ".onnx");
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat /data/local/tmp/lyrics-alignment-" + type + ".onnx");
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = Files.newOutputStream(file.toPath())) {
            input.transferTo(output);
        }
        assertTrue(file.length() > 100_000_000);
        return file;
    }

    private static float[] speech() throws Exception {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(staged("speech.f32").toPath())).order(ByteOrder.LITTLE_ENDIAN);
        float[] audio = new float[bytes.remaining() / 4];
        bytes.asFloatBuffer().get(audio);
        return audio;
    }

    private static byte[] hash(File file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    @Test
    public void startupOverlapAndAlignmentReuse() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("speed", "0").equals("1"));
        File model = model("int8"), recording = staged("long.flac");
        PreparationSpeedTest.runtime();
        float[] clip = Arrays.copyOf(speech(), 80000);
        float[][] expectedEmissions = null;
        byte[] expectedPcm = null;
        for (boolean parallel : new boolean[] {false, true, true, false}) {
            File output = new File(recording.getParentFile(), "speed-decoded.pcm");
            long start = System.nanoTime();
            AlignmentModel alignment = null;
            VocalSeparator separator = null;
            var executor = Executors.newFixedThreadPool(3);
            try {
                AudioCodec.Decoded decoded;
                if (parallel) {
                    var decode = executor.submit(() -> AudioCodec.decodeToPcm(recording, output));
                    var vocal = executor.submit(() -> { return PreparationSpeedTest.separator(); });
                    var align = executor.submit(() -> new AlignmentModel(model));
                    decoded = decode.get();
                    separator = vocal.get();
                    alignment = align.get();
                } else {
                    decoded = AudioCodec.decodeToPcm(recording, output);
                    separator = PreparationSpeedTest.separator();
                    alignment = new AlignmentModel(model);
                }
                double startupMs = (System.nanoTime() - start) / 1e6;
                assertTrue(separator.backend().contains("NPU"));
                assertEquals(44100, decoded.rate());
                byte[] pcmHash = hash(output);
                if (expectedPcm == null) expectedPcm = pcmHash;
                assertArrayEquals(expectedPcm, pcmHash);
                for (int run = 0; run < 3; run++) {
                    start = System.nanoTime();
                    float[][] emissions = alignment.emissions(clip);
                    double elapsed = (System.nanoTime() - start) / 1e6;
                    if (expectedEmissions == null) expectedEmissions = emissions;
                    for (int t = 0; t < emissions.length; t++) assertArrayEquals(expectedEmissions[t], emissions[t], 0);
                    Log.i("LyricsSpeed", "STARTUP parallel=" + parallel + " startup_ms=" + startupMs + " alignment_run=" + run + " inference_ms=" + elapsed + " pcm_and_emissions_exact=true");
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
                if (alignment != null) alignment.close();
                if (separator != null) separator.close();
                Files.deleteIfExists(output.toPath());
            }
        }
    }

    private static OrtSession.SessionOptions options(File runtime, File cache, boolean generate) throws Exception {
        var options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(6);
        options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.addConfigEntry("session.intra_op.allow_spinning", "0");
        options.addConfigEntry("optimization.disable_specified_optimizers", "SimplifiedLayerNormFusion;SkipSimplifiedLayerNormFusion");
        if (runtime != null) {
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            QnnRuntime.configure(options, runtime, "htp", "burst");
            options.addConfigEntry("ep.context_file_path", cache.getAbsolutePath());
            if (generate) {
                options.addConfigEntry("ep.context_enable", "1");
                options.addConfigEntry("ep.context_embed_mode", "0");
            }
        }
        return options;
    }

    private static float[][] emissions(OrtSession session, float[] audio) throws Exception {
        double mean = 0, variance = 0;
        for (float sample : audio) mean += sample;
        mean /= audio.length;
        for (float sample : audio) variance += (sample - mean) * (sample - mean);
        variance /= audio.length;
        float scale = (float) (1 / Math.sqrt(variance + 1e-7));
        float[] normalized = new float[audio.length];
        for (int i = 0; i < audio.length; i++) normalized[i] = (float) ((audio[i] - mean) * scale);
        try (var tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(normalized), new long[] {1, audio.length}); var run = new OrtSession.RunOptions()) {
            run.addRunConfigEntry("qnn.htp_perf_mode", "burst");
            run.addRunConfigEntry("qnn.htp_perf_mode_post_run", "power_saver");
            try (var result = session.run(Map.of("input_values", tensor), run)) {
                float[][] logits = ((float[][][]) result.get(0).getValue())[0];
                float[][] output = new float[logits.length][CtcAligner.CLASSES];
                for (int t = 0; t < logits.length; t++) {
                    float max = Float.NEGATIVE_INFINITY;
                    for (float value : logits[t]) max = Math.max(max, value);
                    double sum = 0;
                    for (float value : logits[t]) sum += Math.exp(value - max);
                    float log = (float) (max + Math.log(sum));
                    for (int c = 0; c < logits[t].length; c++) output[t][c] = logits[t][c] - log;
                    output[t][CtcAligner.STAR] = -20;
                }
                return output;
            }
        }
    }

    private static int[] transcript(float[][] emissions) {
        List<Integer> tokens = new ArrayList<>();
        int previous = -1;
        for (float[] row : emissions) {
            int best = 0;
            for (int i = 1; i < 32; i++) if (row[i] > row[best]) best = i;
            if (best > 0 && best != previous) tokens.add(best);
            previous = best;
        }
        return tokens.stream().mapToInt(Integer::intValue).toArray();
    }

    static String decoded(float[][] emissions) {
        StringBuilder text = new StringBuilder();
        String vocabulary = "???? ETAONIHSRDLUMWCFGYPBVK'XJQZ";
        for (int token : transcript(emissions)) text.append(vocabulary.charAt(token));
        return text.toString();
    }

    static Comparison compare(String label, float[][] reference, float[][] candidate) {
        int nonfinite = 0, blank = 0;
        for (float[] row : candidate) {
            int best = 0;
            for (int c = 0; c < 32; c++) {
                if (!Float.isFinite(row[c])) nonfinite++;
                if (row[c] > row[best]) best = c;
            }
            if (best == CtcAligner.BLANK) blank++;
        }
        Log.i("LyricsSpeed", "NUMERICS " + label + " nonfinite=" + nonfinite + " blank_frames=" + blank + "/" + candidate.length);
        if (nonfinite != 0) return null;
        int[] tokens = transcript(reference);
        assertTrue("Speech fixture yielded no transcript", tokens.length > 0);
        int[] expected = CtcAligner.forcedAlign(reference, tokens, new float[reference.length]);
        int[] actual = CtcAligner.forcedAlign(candidate, tokens, new float[candidate.length]);
        List<Integer> shifts = new ArrayList<>();
        List<int[]> expectedSpans = spans(expected), actualSpans = spans(actual);
        assertEquals(tokens.length, expectedSpans.size());
        assertEquals(tokens.length, actualSpans.size());
        for (int i = 0; i < tokens.length; i++) {
            shifts.add(Math.abs(expectedSpans.get(i)[0] - actualSpans.get(i)[0]) * 20);
            shifts.add(Math.abs(expectedSpans.get(i)[1] - actualSpans.get(i)[1]) * 20);
        }
        shifts.sort(Integer::compare);
        Log.i("LyricsSpeed", "QUALITY " + label + " greedy_equal=" + decoded(reference).equals(decoded(candidate)) + " boundary_median_ms=" + shifts.get(shifts.size() / 2) + " boundary_max_ms=" + shifts.get(shifts.size() - 1) + " reference=" + decoded(reference) + " candidate=" + decoded(candidate));
        return new Comparison(shifts.get(shifts.size() / 2), shifts.get(shifts.size() - 1));
    }

    private static List<int[]> spans(int[] path) {
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < path.length;) {
            int end = i + 1;
            while (end < path.length && path[end] == path[i]) end++;
            if (path[i] != CtcAligner.BLANK) spans.add(new int[] {i, end});
            i = end;
        }
        return spans;
    }

    @Test
    public void npuCacheAndSpeechParity() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("speed", "0").equals("1"));
        File int8 = model("int8"), fp16 = model("fp16"), runtime = PreparationSpeedTest.runtime();
        File cache = new File(fp16.getParentFile(), "speed-alignment-ctx.onnx");
        Files.deleteIfExists(cache.toPath());
        float[] speech = speech();
        var environment = OrtEnvironment.getEnvironment();
        float[][][] references = new float[4][][];
        float[][][] paddedReferences = new float[4][][];
        float[][][] fp16References = new float[4][][];
        float[][][] accelerated = new float[4][][];
        int[] lengths = {38400, 52800, 72000, 80000};
        try (var cpuOptions = options(null, null, false); var cpu = environment.createSession(int8.getAbsolutePath(), cpuOptions); var floatOptions = options(null, null, false); var floating = environment.createSession(fp16.getAbsolutePath(), floatOptions)) {
            for (int i = 0; i < lengths.length; i++) {
                float[] clip = Arrays.copyOfRange(speech, i * 16000, i * 16000 + lengths[i]);
                long start = System.nanoTime();
                references[i] = emissions(cpu, clip);
                Log.i("LyricsSpeed", "CPU_EXACT clip=" + i + " samples=" + clip.length + " inference_ms=" + (System.nanoTime() - start) / 1e6);
                paddedReferences[i] = emissions(cpu, Arrays.copyOf(clip, 80000));
                fp16References[i] = emissions(floating, Arrays.copyOf(clip, 80000));
                compare("padding clip=" + i, references[i], paddedReferences[i]);
                compare("fp16_export clip=" + i, paddedReferences[i], fp16References[i]);
            }
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            long start = System.nanoTime();
            try (var qnnOptions = options(runtime, cache, attempt == 0); var qnn = environment.createSession((attempt == 0 ? fp16 : cache).getAbsolutePath(), qnnOptions)) {
                Log.i("LyricsSpeed", "NPU_CACHE attempt=" + attempt + " setup_ms=" + (System.nanoTime() - start) / 1e6 + " cached_model_bytes=" + cache.length());
                for (int i = 0; i < lengths.length; i++) {
                    float[] clip = Arrays.copyOfRange(speech, i * 16000, i * 16000 + lengths[i]);
                    start = System.nanoTime();
                    float[][] output = emissions(qnn, Arrays.copyOf(clip, 80000));
                    Log.i("LyricsSpeed", "NPU_INFERENCE attempt=" + attempt + " clip=" + i + " inference_ms=" + (System.nanoTime() - start) / 1e6);
                    if (attempt == 0) {
                        accelerated[i] = output;
                        compare("npu_vs_fp16 clip=" + i, fp16References[i], output);
                        compare("npu_vs_current clip=" + i, references[i], output);
                    } else {
                        for (int t = 0; t < output.length; t++) assertArrayEquals(accelerated[i][t], output[t], 1e-5f);
                    }
                }
            }
            assertTrue("Compiled NPU cache missing", cache.isFile());
        }
    }
}
