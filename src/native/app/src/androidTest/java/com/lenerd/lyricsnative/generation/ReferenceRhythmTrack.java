package com.lenerd.lyricsnative.generation;

import java.util.Arrays;

final class ReferenceRhythmTrack implements AutoCloseable {
    private static final int BATCH_HOPS = 256, MEL_BANDS = 128;


    private final int rate, hop;
    private final long envelope, tempo;
    private final float[] pending, batchValues = new float[BATCH_HOPS];
    private final long[] batchBeats = new long[BATCH_HOPS];

    private float[] values = new float[1 << 14];
    private long[] beats = new long[256];
    private int filled, valueCount, beatCount;

    ReferenceRhythmTrack(int rate, float minimumHz, float maximumHz, boolean trackBeats) {
        this.rate = rate;
        this.hop = Math.max(64, Math.round(rate * 256f / 22050f));
        this.pending = new float[hop * BATCH_HOPS];

        envelope = Aubio.newEnvelope(powerOfTwo(hop * 8), hop, rate, MEL_BANDS, minimumHz, maximumHz);
        if (envelope == 0) throw new IllegalStateException("Could not create aubio onset envelope");

        if (trackBeats) {
            tempo = Aubio.newTempo("default", powerOfTwo(hop * 2), hop, rate);

            if (tempo == 0) {
                Aubio.deleteEnvelope(envelope);
                throw new IllegalStateException("Could not create aubio tempo tracker");
            }
        } else {
            tempo = 0;
        }
    }

    private static int powerOfTwo(int size) {
        int lower = Integer.highestOneBit(size);
        return size - lower > lower * 2 - size ? lower * 2 : lower;
    }

    void write(float[] samples, int count) {
        int offset = 0;

        while (offset < count) {
            int chunk = Math.min(count - offset, pending.length - filled);
            System.arraycopy(samples, offset, pending, filled, chunk);
            filled += chunk;
            offset += chunk;

            if (filled == pending.length) flush(BATCH_HOPS);
        }
    }

    RhythmTrack.Result finish() {
        int hops = (filled + hop - 1) / hop;

        if (hops > 0) {
            Arrays.fill(pending, filled, hops * hop, 0);
            flush(hops);
        }

        float bpm = tempo != 0 ? Aubio.tempoBpm(tempo) : 0;
        return new RhythmTrack.Result(rate, hop, Arrays.copyOf(values, valueCount), Arrays.copyOf(beats, beatCount), bpm);
    }

    private void flush(int hops) {
        Aubio.processEnvelopeReference(envelope, pending, hops, batchValues);

        if (valueCount + hops > values.length) values = Arrays.copyOf(values, Math.max(values.length * 2, valueCount + hops));
        System.arraycopy(batchValues, 0, values, valueCount, hops);
        valueCount += hops;

        if (tempo != 0) {
            int found = Aubio.processTempo(tempo, pending, hops, batchBeats);

            if (beatCount + found > beats.length) beats = Arrays.copyOf(beats, Math.max(beats.length * 2, beatCount + found));
            System.arraycopy(batchBeats, 0, beats, beatCount, found);
            beatCount += found;
        }

        filled = 0;
    }

    @Override
    public void close() {
        Aubio.deleteEnvelope(envelope);
        if (tempo != 0) Aubio.deleteTempo(tempo);
    }
}
