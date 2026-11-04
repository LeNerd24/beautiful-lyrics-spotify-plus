package com.lenerd.lyricsnative.generation;

import java.util.Arrays;

final class StreamingResampler {
    interface Sink {
        void write(float[] samples, int count) throws Exception;
    }

    private static final int ZERO_CROSSINGS = 16, TABLE_RESOLUTION = 512;
    private static final double ROLLOFF = 0.94, KAISER_BETA = 8.6;
    private static final java.util.concurrent.ConcurrentHashMap<Long, Kernel> KERNELS = new java.util.concurrent.ConcurrentHashMap<>();

    private final double step, cutoff, halfWidth;
    private final int inputRate, outputRate;
    private final float[] kernel;
    private final double[][] phases;
    private final int radius;
    private final Sink sink;
    private final float[] output = new float[4096];

    private float[] buffer = new float[1 << 15];
    private long base, received, produced;
    private int buffered, outputCount;

    StreamingResampler(int inputRate, int outputRate, Sink sink) {
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.sink = sink;
        step = inputRate / (double) outputRate;
        cutoff = Math.min(1.0, outputRate / (double) inputRate) * ROLLOFF;
        halfWidth = ZERO_CROSSINGS / cutoff;

        Kernel shared = KERNELS.computeIfAbsent(((long) inputRate << 32) | outputRate, ignored -> new Kernel(inputRate, outputRate, cutoff, halfWidth));
        kernel = shared.table;
        phases = shared.phases;
        radius = shared.radius;
    }

    void write(float[] samples, int count) throws Exception {
        if (buffered + count > buffer.length) compact(count);

        System.arraycopy(samples, 0, buffer, buffered, count);
        buffered += count;
        received += count;

        produce(false);
    }

    void finish() throws Exception {
        produce(true);
        if (outputCount > 0) sink.write(output, outputCount);
        outputCount = 0;
    }

    private void produce(boolean last) throws Exception {
        long total = last ? outputFrames(received, inputRate, outputRate) : Long.MAX_VALUE;

        while (produced < total) {
            double t = produced * step;
            if (!last && Math.floor(t + halfWidth) >= received) break;

            long first = (long) Math.ceil(t - halfWidth), end = (long) Math.floor(t + halfWidth);
            double sum = 0;

            if (phases != null) {
                double[] weights = phases[(int) (produced % phases.length)];
                long center = produced * inputRate / outputRate;
                long begin = center - radius;
                int from = (int) Math.max(0, base - begin), to = (int) Math.min(weights.length, received - begin);
                int at = (int) (begin - base + from);
                for (int i = from; i < to; i++) sum += buffer[at++] * weights[i];
            } else {
                for (long k = Math.max(first, base); k <= end && k < received; k++) {
                    double distance = Math.abs(t - k) * TABLE_RESOLUTION;
                    int index = (int) distance;
                    if (index + 1 >= kernel.length) continue;

                    double fraction = distance - index;
                    sum += buffer[(int) (k - base)] * (kernel[index] + (kernel[index + 1] - kernel[index]) * fraction);
                }
            }

            output[outputCount++] = (float) sum;
            produced++;

            if (outputCount == output.length) {
                sink.write(output, outputCount);
                outputCount = 0;
            }
        }
    }

    static long outputFrames(long inputFrames, int inputRate, int outputRate) {
        long scaled = Math.multiplyExact(inputFrames, (long) outputRate);
        return scaled / inputRate + (scaled % inputRate == 0 ? 0 : 1);
    }

    private void compact(int incoming) {
        long keep = Math.max(base, (long) Math.floor(produced * step - halfWidth) - 1);
        int drop = (int) (keep - base);

        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, buffered - drop);
            buffered -= drop;
            base = keep;
        }

        if (buffered + incoming > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, buffered + incoming));
    }

    private static double bessel0(double x) {
        double sum = 1, term = 1, half = x / 2;

        for (int k = 1; k < 50; k++) {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }

        return sum;
    }

    private static final class Kernel {
        final float[] table;
        final double[][] phases;
        final int radius;

        Kernel(int inputRate, int outputRate, double cutoff, double halfWidth) {
            table = new float[(int) Math.ceil(halfWidth * TABLE_RESOLUTION) + 2];
            radius = (int) Math.ceil(halfWidth) + 1;
            double normalization = bessel0(KAISER_BETA);
            for (int i = 0; i < table.length; i++) {
                double x = i / (double) TABLE_RESOLUTION;
                if (x > halfWidth) break;
                double ratio = x / halfWidth, window = bessel0(KAISER_BETA * Math.sqrt(1 - ratio * ratio)) / normalization;
                double u = cutoff * x, sinc = u == 0 ? 1 : Math.sin(Math.PI * u) / (Math.PI * u);
                table[i] = (float) (cutoff * sinc * window);
            }
            int a = inputRate, b = outputRate;
            while (b != 0) { int remainder = a % b; a = b; b = remainder; }
            int count = outputRate / a;
            phases = count <= 512 ? new double[count][2 * radius + 1] : null;
            if (phases == null) return;
            for (int phase = 0; phase < count; phase++) {
                double fraction = ((long) phase * inputRate % outputRate) / (double) outputRate;
                int first = (int) Math.ceil(fraction - halfWidth), last = (int) Math.floor(fraction + halfWidth);
                for (int k = first; k <= last; k++) {
                    double distance = Math.abs(fraction - k) * TABLE_RESOLUTION;
                    int index = (int) distance;
                    if (index + 1 >= table.length) continue;
                    double mix = distance - index;
                    phases[phase][k + radius] = table[index] + (table[index + 1] - table[index]) * mix;
                }
            }
        }
    }
}
