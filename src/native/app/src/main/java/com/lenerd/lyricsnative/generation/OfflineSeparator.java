package com.lenerd.lyricsnative.generation;

import com.lenerd.lyricsnative.sing.RealtimeBuffer;
import com.lenerd.lyricsnative.sing.SpectralTransform;

import java.nio.ShortBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OfflineSeparator {
    public interface Model {
        float[][] separate(float[][] pcm) throws Exception;
    }
    public interface Sink {
        void write(float[][] vocals, float[][] original, int frames) throws Exception;
    }
    public interface Progress {
        void update(double fraction);
    }

    private OfflineSeparator() { }

    static final class WindowCache {
        private static final int LIMIT = 24;
        private final Map<Long, float[][]> windows = new LinkedHashMap<>(LIMIT, 0.75f, true);

        private float[][] get(long start) { return windows.get(start); }

        private void put(long start, float[][] estimate) {
            windows.put(start, estimate);
            if (windows.size() > LIMIT) windows.remove(windows.keySet().iterator().next());
        }
    }

    public static void separate(ShortBuffer pcm, long frames, int rate, Model model, Sink sink, Progress progress) throws Exception {
        separateRange(pcm, frames, rate, 0, frames, model, sink, progress, new WindowCache());
    }

    static void separateRange(ShortBuffer pcm, long frames, int rate, long from, long to, Model model, Sink sink, Progress progress, WindowCache cache) throws Exception {
        if (from < 0 || to < from || to > frames) throw new IllegalArgumentException("Invalid separation range");
        if (from == to) return;
        int windowFrames = scale(SpectralTransform.SAMPLES, rate), marginFrames = scale(SpectralTransform.MARGIN, rate), stepFrames = scale(20480, rate);
        int valid = windowFrames - 2 * marginFrames, overlap = valid - stepFrames;

        float[][] sum = new float[2][valid];
        float[] weights = new float[valid];
        float[][] vocals = new float[2][stepFrames], original = new float[2][stepFrames];

        long first = Math.max(0, from / stepFrames * stepFrames - stepFrames);
        for (long start = first; start < to; start += stepFrames) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

            float[][] estimate = cache.get(start);
            if (estimate == null) {
                float[][] window = new float[2][windowFrames];

                for (int i = 0; i < windowFrames; i++) {
                    long index = start - marginFrames + i;
                    if (index < 0) index = -index;
                    if (index >= frames) index = 2 * (frames - 1) - index;
                    if (index < 0 || index >= frames) continue;

                    for (int c = 0; c < 2; c++) window[c][i] = pcm.get((int) (index * 2 + c)) / 32768f;
                }
                estimate = RealtimeBuffer.resample(model.separate(RealtimeBuffer.resample(window, SpectralTransform.SAMPLES)), windowFrames);
                cache.put(start, estimate);
            }

            for (int i = 0; i < valid; i++) {
                float weight = Math.min(1f, Math.min(i + 1, valid - i) / (float) overlap);
                weights[i] += weight;

                for (int c = 0; c < 2; c++) sum[c][i] += estimate[c][marginFrames + i] * weight;
            }

            int offset = (int) Math.max(0, from - start), count = (int) Math.min(stepFrames, to - start);

            for (int i = offset; i < count; i++) {
                for (int c = 0; c < 2; c++) {
                    vocals[c][i - offset] = sum[c][i] / Math.max(weights[i], 1e-8f);
                    original[c][i - offset] = pcm.get((int) ((start + i) * 2 + c)) / 32768f;
                }
            }

            if (count > offset) sink.write(vocals, original, count - offset);

            for (int c = 0; c < 2; c++) {
                System.arraycopy(sum[c], stepFrames, sum[c], 0, valid - stepFrames);
                java.util.Arrays.fill(sum[c], valid - stepFrames, valid, 0);
            }

            System.arraycopy(weights, stepFrames, weights, 0, valid - stepFrames);
            java.util.Arrays.fill(weights, valid - stepFrames, valid, 0);

            if (progress != null) progress.update(Math.max(0, Math.min(1, (start + count - from) / (double) (to - from))));
        }
    }

    private static int scale(int samples, int rate) { return (int) Math.round(samples * (rate / 44100.0)); }
}
