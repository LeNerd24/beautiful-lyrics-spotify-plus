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
