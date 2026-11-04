package com.lenerd.lyricsnative.sing;

public final class NativeSpectralTransform implements AutoCloseable {
    static { System.loadLibrary("lyricsspectral"); }

    private long handle = create();

    public NativeSpectralTransform() {
        if (handle == 0) throw new IllegalStateException("Cannot create native spectral transform");
    }

    public synchronized float[] forward(float[][] stereo) {
        if (handle == 0) throw new IllegalStateException("Spectral transform is closed");
        if (stereo.length != 2 || stereo[0].length != SpectralTransform.SAMPLES || stereo[1].length != SpectralTransform.SAMPLES) throw new IllegalArgumentException("Wrong spectral input shape");
        return forward(handle, stereo[0], stereo[1]);
    }

    public synchronized float[][] inverse(float[] spectrum) {
        if (handle == 0) throw new IllegalStateException("Spectral transform is closed");
        if (spectrum.length != 4 * SpectralTransform.BINS * SpectralTransform.TIMES) throw new IllegalArgumentException("Wrong spectrum shape");
        return inverse(handle, spectrum);
    }

    @Override
    public synchronized void close() {
        if (handle != 0) destroy(handle);
        handle = 0;
    }

    private static native long create();
    private static native void destroy(long handle);
    private static native float[] forward(long handle, float[] left, float[] right);
    private static native float[][] inverse(long handle, float[] spectrum);
}
