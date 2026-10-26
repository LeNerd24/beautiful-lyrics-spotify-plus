package com.lenerd.lyricsnative.sing;

import java.util.Arrays;

public final class RealtimeBuffer {
    public record Window(float[][] pcm, long generation, long start) { }
    public record Output(byte[] bytes, long generation, long offset, float[] removal) { }

    private final int rate, bytesPerSample, capacity, windowFrames, marginFrames, stepFrames;
    private final float[][] input, vocals;
    private final float[] weights;
    private final long[] inputTags, vocalTags;

    private long captured, rendered, nextWindow, readyThrough, generation, separatedFrames, fallbackFrames;
    private float smoothed;
    private volatile float gain;
    private boolean ended;

    public RealtimeBuffer(int rate, int encoding) {
        if (rate != 44100 && rate != 48000) throw new IllegalArgumentException("Requires 44.1/48 kHz");
        if (encoding != 2 && encoding != 4) throw new IllegalArgumentException("Requires PCM16/float");

        this.rate = rate;
        bytesPerSample = encoding == 2 ? 2 : 4;
        capacity = rate * 4;
        windowFrames = scale(SpectralTransform.SAMPLES);
        marginFrames = scale(SpectralTransform.MARGIN);
        stepFrames = scale(20480);

        input = new float[2][capacity];
        vocals = new float[2][capacity];
        weights = new float[capacity];
        inputTags = new long[capacity];
        vocalTags = new long[capacity];

        reset();
    }

    private int scale(int samples) { return (int) Math.round(samples * (rate / 44100.0)); }
    public int frameBytes() { return 2 * bytesPerSample; }
    public int rate() { return rate; }
    public synchronized long generation() { return generation; }
    public synchronized long captured() { return captured; }
    public synchronized long rendered() { return rendered; }
    public synchronized long separatedFrames() { return separatedFrames; }
    public synchronized long fallbackFrames() { return fallbackFrames; }
    public synchronized boolean ended() { return ended; }
    public void setGain(float value) { gain = Math.max(0, Math.min(1, value)); }

    public synchronized void reset() {
        generation++;
        captured = rendered = nextWindow = readyThrough = separatedFrames = fallbackFrames = 0;
        ended = false;
        smoothed = 0;

        Arrays.fill(inputTags, -1);
        Arrays.fill(vocalTags, -1);
        Arrays.fill(weights, 0);
    }

    public synchronized int capture(byte[] bytes, int length, int allowedBytes) {
        if (ended) return 0;

        int frames = Math.min(Math.min(length, bytes.length), allowedBytes) / frameBytes();
        frames = (int) Math.min(frames, capacity - (captured - rendered));

        for (int i = 0; i < frames; i++) {
            long index = captured + i;
            int k = (int) (index % capacity);
            inputTags[k] = index;

            for (int c = 0; c < 2; c++) input[c][k] = decode(bytes, (i * 2 + c) * bytesPerSample);
        }

        captured += frames;
        return frames * frameBytes();
    }

    public synchronized void end() { ended = true; }

    public synchronized Window takeWindow() {
        if (nextWindow < rendered) nextWindow = ((rendered + stepFrames - 1) / stepFrames) * stepFrames;
        if (nextWindow >= captured) return null;
        if (!ended && captured < nextWindow - marginFrames + windowFrames) return null;

        float[][] pcm = new float[2][windowFrames];

        for (int i = 0; i < windowFrames; i++) {
            long index = nextWindow - marginFrames + i;
            if (index < 0) index = -index;
            if (index >= captured) continue;

            int k = (int) (index % capacity);
            if (inputTags[k] == index)
                for (int c = 0; c < 2; c++) pcm[c][i] = input[c][k];
        }

        Window window = new Window(resample(pcm, SpectralTransform.SAMPLES), generation, nextWindow);
        nextWindow += stepFrames;

        return window;
    }

    public synchronized void publish(Window window, float[][] estimate) {
        if (window.generation != generation) return;

        float[][] scaled = resample(estimate, windowFrames);
        int valid = windowFrames - 2 * marginFrames, overlap = valid - stepFrames;

        for (int i = 0; i < valid; i++) {
            long index = window.start + i;
            if (index >= captured || index < rendered) continue;

            int k = (int) (index % capacity);

            if (vocalTags[k] != index) {
                vocalTags[k] = index;
                weights[k] = 0;
                vocals[0][k] = vocals[1][k] = 0;
            }

            float weight = Math.min(1f, Math.min(i + 1, valid - i) / (float) overlap);
            weights[k] += weight;

            for (int c = 0; c < 2; c++) vocals[c][k] += scaled[c][marginFrames + i] * weight;
        }

        readyThrough = Math.max(readyThrough, Math.min(captured, window.start + stepFrames));
        if (ended && nextWindow >= captured) readyThrough = captured;
    }

    public synchronized Output output(int maxFrames, boolean deadlineFallback) {
        long available = (deadlineFallback ? captured : readyThrough) - rendered;
        int frames = (int) Math.min(maxFrames, Math.max(0, available));
        byte[] bytes = new byte[frames * frameBytes()];
        float[] ramp = new float[frames];
        float current = smoothed, increment = 1f / (rate * .03f);

        for (int i = 0; i < frames; i++) {
            long index = rendered + i;
            int k = (int) (index % capacity);
            boolean hasVocal = vocalTags[k] == index && weights[k] > 1e-8;
            float target = hasVocal ? 1 - gain : 0;

            current += Math.max(-increment, Math.min(increment, target - current));
            ramp[i] = current;

            for (int c = 0; c < 2; c++) {
                float original = inputTags[k] == index ? input[c][k] : 0;
                float vocal = hasVocal ? vocals[c][k] / weights[k] : 0;
                encode(bytes, (2 * i + c) * bytesPerSample, original - current * vocal);
            }
        }

        return new Output(bytes, generation, rendered, ramp);
    }

    public synchronized void commit(Output output, int acceptedBytes) {
        if (output.generation != generation || output.offset != rendered) return;

        int frames = Math.min(acceptedBytes, output.bytes.length) / frameBytes();

        for (int i = 0; i < frames; i++) {
            int k = (int) ((rendered + i) % capacity);

            if (vocalTags[k] == rendered + i && weights[k] > 1e-8) separatedFrames++;
            else fallbackFrames++;
        }

        if (frames > 0) smoothed = output.removal[frames - 1];
        rendered += frames;
    }

    private float decode(byte[] bytes, int offset) {
        if (bytesPerSample == 2) return (short) ((bytes[offset] & 255) | (bytes[offset + 1] << 8)) / 32768f;

        int bits = (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | ((bytes[offset + 2] & 255) << 16) | (bytes[offset + 3] << 24);
        float value = Float.intBitsToFloat(bits);

        return Float.isFinite(value) ? value : 0;
    }

    private void encode(byte[] bytes, int offset, float value) {
        value = Float.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0;
        int bits = bytesPerSample == 2 ? Math.max(-32768, Math.min(32767, Math.round(value * 32768))) : Float.floatToRawIntBits(value);

        for (int b = 0; b < bytesPerSample; b++) bytes[offset + b] = (byte) (bits >>> (8 * b));
    }

    public static float[][] resample(float[][] pcm, int length) {
        if (pcm[0].length == length) return pcm;

        float[][] result = new float[2][length];

        for (int i = 0; i < length; i++) {
            double position = i * (pcm[0].length / (double) length);
            int a = (int) position, b = Math.min(a + 1, pcm[0].length - 1);
            float f = (float) (position - a);

            for (int c = 0; c < 2; c++) result[c][i] = pcm[c][a] * (1 - f) + pcm[c][b] * f;
        }

        return result;
    }
}
