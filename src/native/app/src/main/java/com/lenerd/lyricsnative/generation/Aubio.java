package com.lenerd.lyricsnative.generation;

final class Aubio {
    static { System.loadLibrary("lyricsaubio"); }

    private Aubio() { }

    static native long newEnvelope(int window, int hop, int rate, int bands, float minimumHz, float maximumHz);
    static native void processEnvelope(long envelope, float[] samples, int hops, float[] values);
    static native void deleteEnvelope(long envelope);

    static native long newTempo(String method, int window, int hop, int rate);
    static native int processTempo(long tempo, float[] samples, int hops, long[] beats);
    static native float tempoBpm(long tempo);
    static native void deleteTempo(long tempo);
}
