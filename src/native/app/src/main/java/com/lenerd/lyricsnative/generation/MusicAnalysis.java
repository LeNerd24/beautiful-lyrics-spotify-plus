package com.lenerd.lyricsnative.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class MusicAnalysis {
    record Event(double time, double strength, String kind) { }

    final int rate, hop;
    final double tempo;
    final double[] beats;
    final List<Event> vocalOnsets, instrumentalOnsets, rhythmEvents;
    final double[] vocalOnsetTimes, instrumentalOnsetTimes, rhythmEventTimes;
    final float[] vocalEnvelope, instrumentalEnvelope;

    MusicAnalysis(RhythmTrack.Result vocals, RhythmTrack.Result instrumental) {
        rate = vocals.rate();
        hop = vocals.hop();
        tempo = instrumental.bpm();

        float[] vocalRaw = librosaLayout(vocals.envelope()), instrumentalRaw = librosaLayout(instrumental.envelope());

        vocalEnvelope = normalize(vocalRaw);
        instrumentalEnvelope = normalize(instrumentalRaw);

        vocalOnsets = onsetEvents(vocalRaw, vocalEnvelope);
        instrumentalOnsets = onsetEvents(instrumentalRaw, instrumentalEnvelope);

        beats = Arrays.stream(instrumental.beats()).mapToDouble(this::milliseconds).toArray();
        rhythmEvents = rhythmEvents(beats);

        vocalOnsetTimes = times(vocalOnsets);
        instrumentalOnsetTimes = times(instrumentalOnsets);
        rhythmEventTimes = times(rhythmEvents);
    }

    MusicAnalysis(int rate, int hop, double tempo, double[] beats, List<Event> vocalOnsets, List<Event> instrumentalOnsets, float[] vocalEnvelope, float[] instrumentalEnvelope) {
        this.rate = rate;
        this.hop = hop;
        this.tempo = tempo;
        this.beats = beats;
        this.vocalOnsets = vocalOnsets;
        this.instrumentalOnsets = instrumentalOnsets;
        this.vocalEnvelope = vocalEnvelope;
        this.instrumentalEnvelope = instrumentalEnvelope;
        this.rhythmEvents = rhythmEvents(beats);
        this.vocalOnsetTimes = times(vocalOnsets);
        this.instrumentalOnsetTimes = times(instrumentalOnsets);
        this.rhythmEventTimes = times(rhythmEvents);
    }

    private double milliseconds(long samples) {
        return samples * 1000.0 / rate;
    }

    private static float[] librosaLayout(float[] values) {
        float[] envelope = new float[values.length];
        if (values.length > 1) System.arraycopy(values, 0, envelope, 1, values.length - 1);

        return envelope;
    }

    static float[] normalize(float[] envelope) {
        float[] positive = new float[envelope.length];
        int count = 0;

        for (float value : envelope)
            if (value > 0) positive[count++] = value;
        if (count == 0) return new float[envelope.length];

        Arrays.sort(positive, 0, count);

        double rank = 0.95 * (count - 1);
        int low = (int) Math.floor(rank), high = Math.min(count - 1, low + 1);
        double scale = positive[low] + (positive[high] - positive[low]) * (rank - low);

        if (scale <= 1e-8) scale = positive[count - 1];
        if (scale <= 1e-8) return new float[envelope.length];

        float[] result = new float[envelope.length];
        for (int i = 0; i < envelope.length; i++) result[i] = (float) Math.max(0, Math.min(1, envelope[i] / scale));

        return result;
    }

    private List<Event> onsetEvents(float[] raw, float[] normalized) {
        List<Event> events = new ArrayList<>();

        for (int frame : onsetFrames(raw, rate / (double) hop)) {
            if (frame >= 0 && frame < normalized.length) events.add(new Event(milliseconds((long) frame * hop), normalized[frame], "onset"));
        }

        return events;
    }

    static int[] onsetFrames(float[] envelope, double framesPerSecond) {
        if (envelope.length == 0) return new int[0];

        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (float value : envelope) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        if (max <= 0 && min >= 0) return new int[0];

        double[] x = new double[envelope.length];
        for (int i = 0; i < x.length; i++) x[i] = (envelope[i] - min) / (max - min + Float.MIN_NORMAL);

        int preMax = (int) Math.floor(0.03 * framesPerSecond), postMax = 1;
        int preAvg = (int) Math.floor(0.10 * framesPerSecond), postAvg = (int) Math.floor(0.10 * framesPerSecond) + 1;
        int wait = (int) Math.floor(0.03 * framesPerSecond);
        double delta = 0.07;

        List<Integer> peaks = new ArrayList<>();
        int last = Integer.MIN_VALUE / 2;

        for (int n = 0; n < x.length; n++) {
            if (x[n] <= 0) continue;

            double localMax = Double.NEGATIVE_INFINITY;
            for (int i = Math.max(0, n - preMax); i < Math.min(x.length, n + postMax); i++) localMax = Math.max(localMax, x[i]);
            if (x[n] != localMax) continue;

            double sum = 0;
            int start = Math.max(0, n - preAvg), end = Math.min(x.length, n + postAvg);
            for (int i = start; i < end; i++) sum += x[i];
            if (x[n] < sum / (end - start) + delta) continue;

            if (n > last + wait) {
                peaks.add(n);
                last = n;
            }
        }

        return peaks.stream().mapToInt(Integer::intValue).toArray();
    }

    private static List<Event> rhythmEvents(double[] beats) {
        List<Event> events = new ArrayList<>();

        if (beats.length > 0) {
            for (int i = 0; i < beats.length - 1; i++) {
                double left = beats[i], interval = beats[i + 1] - left;
                if (interval <= 0) continue;

                events.add(new Event(left, 1.0, "beat"));
                events.add(new Event(left + interval * 0.5, 0.80, "eighth"));
                events.add(new Event(left + interval / 3.0, 0.58, "triplet"));
                events.add(new Event(left + interval * 2.0 / 3.0, 0.58, "triplet"));
                events.add(new Event(left + interval * 0.25, 0.45, "sixteenth"));
                events.add(new Event(left + interval * 0.75, 0.45, "sixteenth"));
            }

            events.add(new Event(beats[beats.length - 1], 1.0, "beat"));
        }

        events.sort(Comparator.comparingDouble(Event::time));

        List<Event> merged = new ArrayList<>();

        for (Event event : events) {
            if (!merged.isEmpty() && Math.abs(event.time - merged.get(merged.size() - 1).time) <= 22) {
                if (event.strength > merged.get(merged.size() - 1).strength) merged.set(merged.size() - 1, event);
                continue;
            }

            merged.add(event);
        }

        return merged;
    }

    private static double[] times(List<Event> events) {
        return events.stream().mapToDouble(Event::time).toArray();
    }
}
