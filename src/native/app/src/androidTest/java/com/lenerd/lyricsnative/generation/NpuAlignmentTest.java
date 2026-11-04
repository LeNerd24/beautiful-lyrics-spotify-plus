package com.lenerd.lyricsnative.generation;

import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lenerd.lyricsnative.sing.PreparationSpeedTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NpuAlignmentTest {
    private static File fixtureDirectory() throws Exception {
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "production-alignment");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        stage("lyrics-npu-masked.onnx", new File(directory, NpuAlignment.MODEL));
        stage("lyrics-npu-weights.onnx", new File(directory, NpuAlignment.WEIGHTS));
        return directory;
    }

    private static float[] speech() throws Exception {
        File directory = fixtureDirectory();
        File fixture = stage("lyrics-speed-speech.f32", new File(directory, "speech.f32"));
        var data = ByteBuffer.wrap(Files.readAllBytes(fixture.toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] speech = new float[data.remaining()];
        data.get(speech);
        return speech;
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

    private static String alignFixtureLine(AlignmentModel model, Hyphenator hyphenator, float[] clip) throws Exception {
        var analysis = new MusicAnalysis(16000, 186, 120, new double[] {0, 500, 1000, 1500, 2000, 2500}, java.util.List.of(new MusicAnalysis.Event(600, 1, "onset")), java.util.List.of(), new float[1000], new float[1000]);
        LineAligner.Audio audio = new LineAligner.Audio() {
            @Override
            public int length() { return clip.length; }

            @Override
            public float[] slice(int start, int end) { return Arrays.copyOfRange(clip, start, end); }
        };
        var tokens = SyllableLyrics.tokenize("a man said");
        var aligner = new LineAligner(audio, model::emissions, hyphenator, analysis, new AlignmentTuning(), message -> { }, false);
        var words = aligner.alignLine(java.util.List.of("a", "man", "said"), 350, clip.length / 16.0 - 350);
        return SyllableLyrics.build(java.util.List.of(new SyllableLyrics.Line(false, tokens, words))).toString();
    }

    @Test
    public void concurrentWholeLineResultsMatchSequentialResultsExactly() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("npu", "0").equals("1"));
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File cpu = stage("lyrics-hybrid-int8.onnx", new File(root, "fallback-int8.onnx"));
        var accelerator = AlignmentModel.class.getDeclaredField("npu");
        accelerator.setAccessible(true);
        float[] speech = speech();
        float[][] clips = new float[12][];
        for (int i = 0; i < clips.length; i++) clips[i] = Arrays.copyOfRange(speech, i * 4000, i * 4000 + (i % 2 == 0 ? 24000 : 56000));
        try (var model = new AlignmentModel(cpu)) {
            accelerator.set(model, new NpuAlignment(fixtureDirectory(), PreparationSpeedTest.runtime()));
            var hyphenator = Hyphenator.bundled();
            var sequential = new java.util.ArrayList<String>();
            for (float[] clip : clips) sequential.add(alignFixtureLine(model, hyphenator, clip));
            var parallel = new java.util.ArrayList<String>();
            try (var tasks = new OrderedTasks<String>(model.parallelism(), parallel::add)) {
                for (float[] clip : clips) tasks.submit(() -> alignFixtureLine(model, hyphenator, clip));
                tasks.finish();
            }
            assertEquals(sequential, parallel);
            Log.i("LineThroughput", "FULL_LINES count=" + clips.length + " final_json_exact=true");
        }
    }

    @Test
    public void blankWindowUsesCpuWithoutDisablingLaterNpuAlignment() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("npu", "0").equals("1"));
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File cpu = stage("lyrics-hybrid-int8.onnx", new File(root, "fallback-int8.onnx"));
        var accelerator = AlignmentModel.class.getDeclaredField("npu");
        accelerator.setAccessible(true);
        try (var expected = new AlignmentModel(cpu); var actual = new AlignmentModel(cpu)) {
            var npu = new NpuAlignment(fixtureDirectory(), PreparationSpeedTest.runtime());
            accelerator.set(actual, npu);
            float[] tone = new float[48000];
            for (int i = 0; i < tone.length; i++) tone[i] = (float) (0.1 * Math.sin(i * 0.05));
            assertThrows(NpuAlignment.BlankPrediction.class, () -> npu.logits(normalize(tone)));
            float[][] reference = expected.emissions(tone), fallback = actual.emissions(tone);
            for (int t = 0; t < reference.length; t++) assertArrayEquals(reference[t], fallback[t], 0);
            assertSame(npu, accelerator.get(actual));
            float[] voice = Arrays.copyOf(speech(), 64000);
            float[][] first = actual.emissions(voice), second = actual.emissions(voice);
            for (int t = 0; t < first.length; t++) assertArrayEquals(first[t], second[t], 0);
            assertSame(npu, accelerator.get(actual));
            Log.i("LineThroughput", "BLANK_RECOVERY cpu_fallback_exact=true next_npu_available=true");
        }
    }

    @Test
    public void parallelCpuAndNpuEmissionsAreBitExactAndBenchmarked() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("npu", "0").equals("1"));
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File cpu = stage("lyrics-hybrid-int8.onnx", new File(root, "fallback-int8.onnx"));
        var accelerator = AlignmentModel.class.getDeclaredField("npu");
        accelerator.setAccessible(true);
        float[] speech = speech();
        float[][] clips = new float[24][];
        for (int i = 0; i < clips.length; i++) {
            int length = new int[] {20000, 48000, 24000, 64000}[i % 4];
            clips[i] = Arrays.copyOfRange(speech, i * 3000, i * 3000 + length);
        }
        try (var model = new AlignmentModel(cpu)) {
            accelerator.set(model, new NpuAlignment(fixtureDirectory(), PreparationSpeedTest.runtime()));
            float[][][] expected = new float[clips.length][][];
            for (int repeat = 0; repeat < 4; repeat++) {
                boolean parallel = repeat % 2 != 0;
                var outputs = new java.util.ArrayList<float[][]>();
                long started = System.nanoTime();
                if (parallel) {
                    try (var tasks = new OrderedTasks<float[][]>(2, outputs::add)) {
                        for (float[] clip : clips) tasks.submit(() -> model.emissions(clip));
                        tasks.finish();
                    }
                } else {
                    for (float[] clip : clips) outputs.add(model.emissions(clip));
                }
                double elapsed = (System.nanoTime() - started) / 1e6;
                assertEquals(clips.length, outputs.size());
                for (int i = 0; i < clips.length; i++) {
                    float[][] result = outputs.get(i);
                    if (repeat == 0) expected[i] = result;
                    assertEquals(expected[i].length, result.length);
                    for (int t = 0; t < result.length; t++) assertArrayEquals(expected[i][t], result[t], 0);
                }
                Log.i("LineThroughput", "MIXED repeat=" + repeat + " parallel=" + parallel + " elapsed_ms=" + elapsed + " clips=" + clips.length + " exact=true");
            }
        }
    }

    @Test
    public void measuredSongWindowLengthsCompareWorkerCounts() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("widths", "0").equals("1"));
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File cpu = stage("lyrics-hybrid-int8.onnx", new File(root, "fallback-int8.onnx"));
        var accelerator = AlignmentModel.class.getDeclaredField("npu");
        accelerator.setAccessible(true);
        int[] lengths = {43280, 44320, 36112, 40560, 45344, 47024, 40896, 38848, 43632, 45328, 36928, 25728, 39184, 16544, 16656, 5456, 15968, 4768, 38848, 15632, 16320, 5120, 16656, 5456, 54032, 42832, 40224, 49088, 37824, 46368, 43968, 44992};
        float[] speech = speech();
        float[][][] expected = new float[lengths.length][][];
        try (var model = new AlignmentModel(cpu)) {
            accelerator.set(model, new NpuAlignment(fixtureDirectory(), PreparationSpeedTest.runtime()));
            for (int workers : new int[] {1, 2, 3, 4, 4, 3, 2, 1}) {
                var outputs = new java.util.ArrayList<float[][]>();
                long started = System.nanoTime();
                try (var tasks = new OrderedTasks<float[][]>(workers, outputs::add)) {
                    for (int i = 0; i < lengths.length; i++) {
                        float[] clip = Arrays.copyOfRange(speech, i * 2000, i * 2000 + lengths[i]);
                        tasks.submit(() -> model.emissions(clip));
                    }
                    tasks.finish();
                }
                double elapsed = (System.nanoTime() - started) / 1e6;
                for (int i = 0; i < lengths.length; i++) {
                    if (expected[i] == null) expected[i] = outputs.get(i);
                    assertEquals(expected[i].length, outputs.get(i).length);
                    for (int t = 0; t < expected[i].length; t++) assertArrayEquals(expected[i][t], outputs.get(i)[t], 0);
                }
                Log.i("LineThroughput", "WORKERS count=" + workers + " elapsed_ms=" + elapsed + " clips=" + lengths.length + " exact=true");
            }
        }
    }

    private static File stage(String remote, File target) throws Exception {
        if (target.isFile() && target.length() > 1000) return target;
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat /data/local/tmp/" + remote);
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = Files.newOutputStream(target.toPath())) {
            input.transferTo(output);
        }
        assertTrue(remote, target.length() > 1000);
        return target;
    }

    @Test
    public void externalWeightsAndPersistentCache() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("npu", "0").equals("1"));
        File directory = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "production-alignment");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        stage("lyrics-npu-masked.onnx", new File(directory, NpuAlignment.MODEL));
        stage("lyrics-npu-weights.onnx", new File(directory, NpuAlignment.WEIGHTS));
        File speech = stage("lyrics-speed-speech.f32", new File(directory, "speech.f32"));
        ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(speech.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        float[] input = new float[80000];
        data.asFloatBuffer().get(input);
        double mean = 0, variance = 0;
        for (float value : input) mean += value;
        mean /= input.length;
        for (float value : input) variance += (value - mean) * (value - mean);
        variance /= input.length;
        float scale = (float) (1 / Math.sqrt(variance + 1e-7));
        for (int i = 0; i < input.length; i++) input[i] = (float) ((input[i] - mean) * scale);
        float[][] expected = null;
        File reference = new File(directory, "expected.f32");
        if (reference.isFile()) {
            var bytes = ByteBuffer.wrap(Files.readAllBytes(reference.toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            expected = new float[249][32];
            for (float[] row : expected) bytes.get(row);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            long start = System.nanoTime();
            try (var model = new NpuAlignment(directory, PreparationSpeedTest.runtime())) {
                double setup = (System.nanoTime() - start) / 1e6;
                start = System.nanoTime();
                float[][] output = model.logits(input);
                double elapsed = (System.nanoTime() - start) / 1e6;
                if (expected == null) {
                    expected = output;
                    ByteBuffer saved = ByteBuffer.allocate(249 * 32 * 4).order(ByteOrder.LITTLE_ENDIAN);
                    for (float[] row : output) for (float value : row) saved.putFloat(value);
                    Files.write(reference.toPath(), saved.array());
                }
                assertEquals(249, output.length);
                for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], output[i], 0);
                int voiced = 0;
                for (float[] row : output) {
                    int best = 0;
                    for (int c = 0; c < 32; c++) {
                        assertTrue(Float.isFinite(row[c]));
                        if (row[c] > row[best]) best = c;
                    }
                    if (best != 0) voiced++;
                }
                assertTrue("Lost speech predictions", voiced > 30);
                assertFalse(NpuAlignment.accepts(1000));
                assertFalse(NpuAlignment.accepts(80001));
                assertTrue(NpuAlignment.accepts(32000));
                Log.i("NpuAlignmentTest", "PASS attempt=" + attempt + " setup_ms=" + setup + " inference_ms=" + elapsed + " voiced_frames=" + voiced + " bit_exact=true");
            }
        }
    }

    @Test
    public void cpuOnlyConstructionAndSilenceRemainValid() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("npu", "0").equals("1"));
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File model = stage("lyrics-hybrid-int8.onnx", new File(directory, "fallback-int8.onnx"));
        try (var alignment = new AlignmentModel(model)) {
            for (int samples : new int[] {16000, 80001}) {
                float[][] emissions = alignment.emissions(new float[samples]);
                assertTrue(emissions.length > 0);
                for (float[] row : emissions) for (float value : row) assertTrue(Float.isFinite(value));
            }
        }
        Log.i("NpuAlignmentTest", "PASS CPU fallback constructor, silence, short and longer windows");
    }

    @Test
    public void inferenceFailureRetriesOnCpuExactly() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("npu", "0").equals("1"));
        File root = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File directory = new File(root, "production-alignment");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        stage("lyrics-npu-masked.onnx", new File(directory, NpuAlignment.MODEL));
        stage("lyrics-npu-weights.onnx", new File(directory, NpuAlignment.WEIGHTS));
        File model = stage("lyrics-hybrid-int8.onnx", new File(root, "fallback-int8.onnx"));
        try (var broken = new NpuAlignment(directory, PreparationSpeedTest.runtime()); var expected = new AlignmentModel(model); var actual = new AlignmentModel(model)) {
            broken.close();
            var accelerator = AlignmentModel.class.getDeclaredField("npu");
            accelerator.setAccessible(true);
            accelerator.set(actual, broken);
            float[] tone = new float[48000];
            for (int i = 0; i < tone.length; i++) tone[i] = (float) (0.1 * Math.sin(i * 0.05));
            float[][] reference = expected.emissions(tone), fallback = actual.emissions(tone);
            for (int t = 0; t < reference.length; t++) assertArrayEquals(reference[t], fallback[t], 0);
            assertNull(accelerator.get(actual));
            Log.i("NpuAlignmentTest", "PASS failed NPU inference retried on CPU with bit-exact emissions");
        }
    }
}
