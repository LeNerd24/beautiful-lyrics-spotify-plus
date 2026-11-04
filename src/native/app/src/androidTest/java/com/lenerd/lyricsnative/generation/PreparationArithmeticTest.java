package com.lenerd.lyricsnative.generation;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreparationArithmeticTest {
    @Test
    public void nativeResamplerMatchesJavaAtStreamingEdges() throws Exception {
        var random = new Random(715);
        for (int rate : new int[] {16000, 22050, 44100, 48000, 47999}) {
            for (int length : new int[] {0, 1, 37, 21001, 100003}) {
                float[] input = new float[length];
                for (int i = 0; i < length; i++) input[i] = (float) (random.nextGaussian() * 0.1);
                float[][] outputs = new float[2][(int) StreamingResampler.outputFrames(length, rate, 16000)];
                for (int pass = 0; pass < 2; pass++) {
                    float[] output = outputs[pass];
                    int[] written = {0};
                    var resampler = new StreamingResampler(rate, 16000, (samples, count) -> {
                        System.arraycopy(samples, 0, output, written[0], count);
                        written[0] += count;
                    }, pass == 1);
                    int packet = pass == 0 ? 113 : 17003;
                    for (int at = 0; at < length; at += packet) {
                        int end = Math.min(length, at + packet);
                        resampler.write(Arrays.copyOfRange(input, at, end), end - at);
                    }
                    resampler.finish();
                    assertEquals(output.length, written[0]);
                }
                assertArrayEquals("rate=" + rate + " length=" + length, outputs[0], outputs[1], 1e-7f);
            }
        }
    }

    private static float[] resample(float[] input, int rate, boolean reference, int packet) throws Exception {
        float[] output = new float[(int) StreamingResampler.outputFrames(input.length, rate, 16000)];
        int[] written = {0};
        if (reference) {
            var resampler = new ReferenceStreamingResampler(rate, 16000, (samples, count) -> { System.arraycopy(samples, 0, output, written[0], count); written[0] += count; });
            for (int at = 0; at < input.length; at += packet) resampler.write(Arrays.copyOfRange(input, at, Math.min(input.length, at + packet)), Math.min(packet, input.length - at));
            resampler.finish();
        } else {
            var resampler = new StreamingResampler(rate, 16000, (samples, count) -> { System.arraycopy(samples, 0, output, written[0], count); written[0] += count; });
            for (int at = 0; at < input.length; at += packet) resampler.write(Arrays.copyOfRange(input, at, Math.min(input.length, at + packet)), Math.min(packet, input.length - at));
            resampler.finish();
        }
        assertEquals(output.length, written[0]);
        return output;
    }

    @Test
    public void resamplingPreservesSamplesAndTiming() throws Exception {
        var random = new Random(14);
        for (int rate : new int[] {16000, 22050, 44100, 48000}) {
            float[] input = new float[rate * 5 + 37];
            for (int i = 0; i < input.length; i++) input[i] = (float) (0.1 * Math.sin(i * 0.05) + 0.05 * random.nextGaussian());
            float[] expected = resample(input, rate, true, 1999);
            for (int packet : new int[] {127, 8192}) {
                float[] actual = resample(input, rate, false, packet);
                double maxError = 0;
                for (int i = 0; i < actual.length; i++) maxError = Math.max(maxError, Math.abs(expected[i] - actual[i]));
                assertTrue("Resampling error " + maxError, maxError < 2e-6);
                Log.i("PreparationBenchmark", "RESAMPLE_PARITY rate=" + rate + " packet=" + packet + " peak_error=" + maxError + " frames=" + actual.length);
            }
            for (int repeat = 0; repeat < 3; repeat++) {
                long start = System.nanoTime();
                resample(input, rate, repeat % 2 == 0, 8192);
                double first = (System.nanoTime() - start) / 1e6;
                start = System.nanoTime();
                resample(input, rate, repeat % 2 != 0, 8192);
                double second = (System.nanoTime() - start) / 1e6;
                Log.i("PreparationBenchmark", "RESAMPLE_SPEED rate=" + rate + " repeat=" + repeat + " reference_ms=" + (repeat % 2 == 0 ? first : second) + " cached_ms=" + (repeat % 2 == 0 ? second : first));
            }
        }
    }

    @Test
    public void sparseRhythmFilterMatchesOriginal() throws Exception {
        int hop = 512, hops = 1024;
        float[] input = new float[hop * hops];
        var random = new Random(912);
        for (int i = 0; i < input.length; i++) input[i] = (float) ((0.05 + 0.15 * Math.exp(-(i % 22050) / 1200.0)) * (Math.sin(i * 0.04) + 0.3 * random.nextGaussian()));
        for (int repeat = 0; repeat < 4; repeat++) {
            long dense = Aubio.newEnvelope(4096, hop, 44100, 128, 80, 8000), sparse = Aubio.newEnvelope(4096, hop, 44100, 128, 80, 8000);
            assertNotEquals(0, dense);
            assertNotEquals(0, sparse);
            try {
                float[] expected = new float[hops], actual = new float[hops];
                long start = System.nanoTime();
                if ((repeat & 1) == 0) Aubio.processEnvelopeReference(dense, input, hops, expected);
                else Aubio.processEnvelope(sparse, input, hops, actual);
                double first = (System.nanoTime() - start) / 1e6;
                start = System.nanoTime();
                if ((repeat & 1) == 0) Aubio.processEnvelope(sparse, input, hops, actual);
                else Aubio.processEnvelopeReference(dense, input, hops, expected);
                double second = (System.nanoTime() - start) / 1e6;
                double maxError = 0;
                for (int i = 0; i < hops; i++) maxError = Math.max(maxError, Math.abs(expected[i] - actual[i]));
                assertTrue("Rhythm envelope error=" + maxError, maxError < 1e-4);
                Log.i("PreparationBenchmark", "RHYTHM_SPEED repeat=" + repeat + " reference_ms=" + ((repeat & 1) == 0 ? first : second) + " sparse_ms=" + ((repeat & 1) == 0 ? second : first) + " peak_error=" + maxError);
            } finally {
                Aubio.deleteEnvelope(dense);
                Aubio.deleteEnvelope(sparse);
            }
        }
    }
}
