package com.lenerd.lyricsnative.generation;

final class SpotifyAudioNative {
    static { System.loadLibrary("lyricsaudio"); }

    private SpotifyAudioNative() { }

    static synchronized native boolean start(String path);
    static native long epoch();
    static native byte[] resources();
    static native boolean armCache(long epoch, byte[] fileId, byte[] trackGid);
    static native boolean cacheFileReady(long epoch);
    static native boolean cacheCipherReady(long epoch);
    static native byte[] decodedCache(long epoch);
    static native void deactivateEpoch(long expected);
    static synchronized native void stop();
}
