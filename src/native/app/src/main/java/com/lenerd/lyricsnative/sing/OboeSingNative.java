package com.lenerd.lyricsnative.sing;

final class OboeSingNative {
    static {
        System.loadLibrary("shadowhook");
        System.loadLibrary("lyricsoboe");
    }

    static native boolean install();
    static native void enable(boolean enabled);
    static native void gain(float value);
    static native void reset();
    static native int read(float[] samples, long[] metadata);
    static native boolean write(float[] samples, int frames, long generation, long start);
    static native long[] state();
    static native void close();

    private OboeSingNative() { }
}
