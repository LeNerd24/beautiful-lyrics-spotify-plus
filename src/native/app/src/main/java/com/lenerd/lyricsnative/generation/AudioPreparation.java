package com.lenerd.lyricsnative.generation;

import android.util.Log;
import java.nio.ShortBuffer;
import java.util.Locale;

final class AudioPreparation {
    record Result(float[] vocals, MusicAnalysis analysis, double offset) { }
    private static final String TAG = "LyricsGeneration";

    static Result prepare(ShortBuffer source, AudioCodec.Decoded decoded, GenerationPlan.Chunk chunk, OfflineSeparator.Model model, OfflineSeparator.WindowCache cache, java.util.function.BooleanSupplier cancelled) throws Exception {
        long started = System.nanoTime();
        int start = (int) Math.round(chunk.start() * decoded.rate() / 1000), end = (int) Math.min(decoded.frames(), Math.ceil(chunk.end() * decoded.rate() / 1000));
        float[] vocals = new float[Math.toIntExact(StreamingResampler.outputFrames(end - start, decoded.rate(), AlignmentModel.SAMPLE_RATE))];
        int[] written = {0};
        float[][] mono = new float[2][0];
        long[] modelNanos = {0}, analysisNanos = {0};
        int[] modelWindows = {0};

        try (RhythmTrack vocalRhythm = new RhythmTrack(decoded.rate(), 80, 8000, false); RhythmTrack instrumentalRhythm = new RhythmTrack(decoded.rate(), 0, 11025, true)) {
            StreamingResampler resampler = new StreamingResampler(decoded.rate(), AlignmentModel.SAMPLE_RATE, (samples, count) -> {
                System.arraycopy(samples, 0, vocals, written[0], count);
                written[0] += count;
            });
            OfflineSeparator.separateRange(source, decoded.frames(), decoded.rate(), start, end, window -> {
                long modelStarted = System.nanoTime();
                float[][] result = model.separate(window);
                modelNanos[0] += System.nanoTime() - modelStarted;
                modelWindows[0]++;
                return result;
            }, (voice, original, count) -> {
                long analysisStarted = System.nanoTime();
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                if (mono[0].length < count) mono[0] = new float[count];
                if (mono[1].length < count) mono[1] = new float[count];

                for (int i = 0; i < count; i++) {
                    mono[0][i] = (voice[0][i] + voice[1][i]) / 2;
                    mono[1][i] = (original[0][i] + original[1][i]) / 2 - mono[0][i];
                }
                vocalRhythm.write(mono[0], count);
                instrumentalRhythm.write(mono[1], count);
                resampler.write(mono[0], count);
                analysisNanos[0] += System.nanoTime() - analysisStarted;
            }, null, cache);
            resampler.finish();
            Log.i(TAG, String.format(Locale.ROOT, "Audio section %.1f–%.1fs prepared in %.2fs", chunk.start() / 1000, chunk.end() / 1000, (System.nanoTime() - started) / 1e9));
            long finishing = System.nanoTime();
            MusicAnalysis analysis = new MusicAnalysis(vocalRhythm.finish(), instrumentalRhythm.finish());
            Log.i(TAG, String.format(Locale.ROOT, "PERF section_prepared start_ms=%.2f end_ms=%.2f total_ms=%.2f model_windows=%d model_ms=%.2f rhythm_resample_ms=%.2f finish_ms=%.2f", chunk.start(), chunk.end(), (System.nanoTime() - started) / 1e6, modelWindows[0], modelNanos[0] / 1e6, analysisNanos[0] / 1e6, (System.nanoTime() - finishing) / 1e6));
            return new Result(written[0] == vocals.length ? vocals : java.util.Arrays.copyOf(vocals, written[0]), analysis, start * 1000.0 / decoded.rate());
        }
    }
}
