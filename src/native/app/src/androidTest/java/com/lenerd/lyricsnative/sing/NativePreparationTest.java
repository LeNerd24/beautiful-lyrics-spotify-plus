package com.lenerd.lyricsnative.sing;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativePreparationTest {
    @Test
    public void reusableTensorsPreserveCpuAndNpuPredictions() throws Exception {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("preparation", "0").equals("1"));
        var buffered = VocalSeparator.class.getDeclaredMethod("predictBuffered", float[].class);
        var unbuffered = VocalSeparator.class.getDeclaredMethod("predictUnbuffered", float[].class);
        buffered.setAccessible(true);
        unbuffered.setAccessible(true);
        float[][] input = new float[2][SpectralTransform.SAMPLES];
        var random = new Random(913);
        for (boolean npu : new boolean[] {false, true}) {
            try (var model = npu ? PreparationSpeedTest.separator() : new VocalSeparator(VocalSeparator.bundledModel())) {
                for (int trial = 0; trial < 4; trial++) {
                    for (float[] channel : input) for (int i = 0; i < channel.length; i++) channel[i] = (float) (random.nextGaussian() * 0.1);
                    float[] features = new SpectralTransform().forward(input);
                    float[] expected = (float[]) unbuffered.invoke(model, (Object) features);
                    float[] actual = (float[]) buffered.invoke(model, (Object) features);
                    assertArrayEquals(expected, actual, 0);
                }
            }
        }
    }

    @Test
    public void generationCacheReopensAndRecovers() throws Exception {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("preparation", "0").equals("1"));
        float[][] input = new float[2][SpectralTransform.SAMPLES], expected = null;
        var random = new Random(421);
        for (float[] channel : input) for (int i = 0; i < channel.length; i++) channel[i] = (float) (0.1 * random.nextGaussian());
        for (int attempt = 0; attempt < 3; attempt++) {
            long started = System.nanoTime();
            try (var model = PreparationSpeedTest.separator(true, false)) {
                double setup = (System.nanoTime() - started) / 1e6;
                assertTrue(model.backend().contains("NPU"));
                float[][] actual = model.separate(input);
                if (expected == null) expected = actual;
                for (int c = 0; c < 2; c++) assertArrayEquals(expected[c], actual[c], 0);
                Log.i("PreparationBenchmark", "PRODUCTION_CACHE attempt=" + attempt + " setup_ms=" + setup + " exact=true");
            }
            if (attempt == 1) {
                var root = PreparationSpeedTest.runtime().getParentFile();
                var directories = root.listFiles(file -> file.isDirectory() && file.getName().startsWith("vocal-"));
                assertNotNull(directories);
                assertTrue(directories.length > 0);
                for (var directory : directories) java.nio.file.Files.write(new java.io.File(directory, "model_ctx.onnx").toPath(), new byte[] {1, 2, 3});
            }
        }
    }

    @Test
    public void inverseHandlesIndependentChannelsAndDc() throws Exception {
        var reference = new SpectralTransform();
        try (var nativeTransform = new NativeSpectralTransform()) {
            var random = new Random(413);
            float[] spectrum = new float[4 * SpectralTransform.BINS * SpectralTransform.TIMES];
            for (int channel = 0; channel < 2; channel++) {
                Arrays.fill(spectrum, 0);
                int from = channel * 2 * SpectralTransform.BINS * SpectralTransform.TIMES;
                for (int i = from; i < from + 2 * SpectralTransform.BINS * SpectralTransform.TIMES; i++) spectrum[i] = (float) random.nextGaussian();
                float[][] expected = reference.inverse(spectrum), actual = nativeTransform.inverse(spectrum);
                for (int c = 0; c < 2; c++) {
                    for (int i = 0; i < expected[c].length; i++) assertEquals(expected[c][i], actual[c][i], 2e-7f);
                }
            }
        }
    }

    @Test
    public void generationBurstModeBenchmark() throws Exception {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("preparation", "0").equals("1"));
        float[][] input = new float[2][SpectralTransform.SAMPLES], expected = null;
        for (int c = 0; c < 2; c++) for (int i = 0; i < input[c].length; i++) input[c][i] = (float) (0.1 * Math.sin(i * (c == 0 ? 0.03 : 0.04)));
        for (boolean hold : new boolean[] {false, true, true, false}) {
            try (var model = PreparationSpeedTest.separator(true, hold)) {
                assertTrue(model.backend().contains("NPU"));
                model.separate(input);
                double[] times = new double[9];
                for (int i = 0; i < times.length; i++) {
                    long start = System.nanoTime();
                    float[][] output = model.separate(input);
                    times[i] = (System.nanoTime() - start) / 1e6;
                    if (expected == null) expected = output;
                    for (int c = 0; c < 2; c++) assertArrayEquals(expected[c], output[c], 1e-5f);
                }
                Arrays.sort(times);
                Log.i("PreparationBenchmark", "BURST_MODE hold=" + hold + " window_median_ms=" + times[4]);
            }
        }
    }

    private static double relativeError(float[] expected, float[] actual) {
        double difference = 0, energy = 0;
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertTrue(Float.isFinite(actual[i]));
            difference += Math.pow(expected[i] - actual[i], 2);
            energy += expected[i] * expected[i];
        }
        return Math.sqrt(difference / Math.max(energy, 1e-20));
    }

    @Test
    public void nativeFftMatchesJavaAndIsBenchmarked() throws Exception {
        var reference = new SpectralTransform();
        try (var nativeTransform = new NativeSpectralTransform()) {
            var random = new Random(69);
            float[][] input = new float[2][SpectralTransform.SAMPLES];
            for (int trial = 0; trial < 4; trial++) {
                for (int c = 0; c < 2; c++) {
                    for (int i = 0; i < input[c].length; i++) input[c][i] = trial == 0 ? 0 : trial == 1 ? (i == 1901 ? 1 : 0) : trial == 2 ? (float) (0.1 * Math.sin(i * 0.08 * (c + 1))) : (float) (0.1 * random.nextGaussian());
                }
                float[] expected = reference.forward(input), actual = nativeTransform.forward(input);
                double forwardError = relativeError(expected, actual);
                assertTrue("Forward FFT error=" + forwardError, forwardError < 2e-6);
                float[][] expectedPcm = reference.inverse(expected), actualPcm = nativeTransform.inverse(expected);
                for (int c = 0; c < 2; c++) assertTrue("Inverse FFT mismatch", relativeError(expectedPcm[c], actualPcm[c]) < 2e-6);
                Log.i("PreparationBenchmark", "FFT_PARITY trial=" + trial + " forward_relative_error=" + forwardError);
            }
            for (int repeat = 0; repeat < 3; repeat++) {
                double[] javaTimes = new double[9], nativeTimes = new double[9];
                for (int run = -3; run < javaTimes.length; run++) {
                    for (int order = 0; order < 2; order++) {
                        boolean useNative = ((run + order) & 1) == 0;
                        long start = System.nanoTime();
                        float[][] result = useNative ? nativeTransform.inverse(nativeTransform.forward(input)) : reference.inverse(reference.forward(input));
                        assertTrue(Float.isFinite(result[0][1000]));
                        if (run >= 0) (useNative ? nativeTimes : javaTimes)[run] = (System.nanoTime() - start) / 1e6;
                    }
                }
                Arrays.sort(javaTimes);
                Arrays.sort(nativeTimes);
                Log.i("PreparationBenchmark", "FFT_SPEED repeat=" + repeat + " java_ms=" + javaTimes[4] + " native_ms=" + nativeTimes[4]);
            }
        }
    }
}
