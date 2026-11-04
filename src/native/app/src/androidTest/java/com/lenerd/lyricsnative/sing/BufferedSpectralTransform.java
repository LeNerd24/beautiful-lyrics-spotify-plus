package com.lenerd.lyricsnative.sing;

import org.jtransforms.fft.FloatFFT_1D;

public final class BufferedSpectralTransform {
    public static final int FFT = 6144, HOP = 1024, BINS = 2048, TIMES = 32;
    public static final int SAMPLES = HOP * (TIMES - 1), MARGIN = FFT / 2;

    private final FloatFFT_1D fft = new FloatFFT_1D(FFT);
    private final float[] window = new float[FFT];
    private final float[] frame = new float[2 * FFT], normalizer = new float[SAMPLES];

    public BufferedSpectralTransform() {
        for (int i = 0; i < FFT; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / FFT));
        for (int t = 0; t < TIMES; t++) {
            for (int j = 0; j < FFT; j++) {
                int k = t * HOP + j - MARGIN;
                if (k >= 0 && k < SAMPLES) normalizer[k] += window[j] * window[j];
            }
        }
        pl.edu.icm.jlargearrays.ConcurrencyUtils.setNumberOfThreads(1);
    }

    public synchronized float[] forward(float[][] stereo) {
        float[] spectrum = new float[4 * BINS * TIMES];

        for (int c = 0; c < 2; c++) {
            for (int t = 0; t < TIMES; t++) {
                java.util.Arrays.fill(frame, 0);

                for (int j = 0; j < FFT; j++) {
                    int k = t * HOP + j - MARGIN;
                    if (k < 0) k = -k;
                    if (k >= SAMPLES) k = 2 * SAMPLES - 2 - k;

                    frame[j] = stereo[c][k] * window[j];
                }

                fft.realForwardFull(frame);

                for (int f = 3; f < BINS; f++) {
                    spectrum[(2 * c * BINS + f) * TIMES + t] = frame[2 * f];
                    spectrum[((2 * c + 1) * BINS + f) * TIMES + t] = frame[2 * f + 1];
                }
            }
        }

        return spectrum;
    }

    public synchronized float[][] inverse(float[] spectrum) {
        float[][] stereo = new float[2][SAMPLES];

        for (int c = 0; c < 2; c++) {
            for (int t = 0; t < TIMES; t++) {
                java.util.Arrays.fill(frame, 0);

                for (int f = 0; f < BINS; f++) {
                    float re = spectrum[(2 * c * BINS + f) * TIMES + t], im = spectrum[((2 * c + 1) * BINS + f) * TIMES + t];

                    frame[2 * f] = re;
                    frame[2 * f + 1] = im;

                    if (f > 0) {
                        frame[2 * (FFT - f)] = re;
                        frame[2 * (FFT - f) + 1] = -im;
                    }
                }

                fft.complexInverse(frame, true);

                for (int j = 0; j < FFT; j++) {
                    int k = t * HOP + j - MARGIN;
                    if (k < 0 || k >= SAMPLES) continue;

                    stereo[c][k] += frame[2 * j] * window[j];
                }
            }
        }

        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < SAMPLES; i++) {
                stereo[c][i] /= Math.max(normalizer[i], 1e-8f);
            }
        }

        return stereo;
    }
}
