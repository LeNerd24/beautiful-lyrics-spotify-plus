package com.lenerd.lyricsnative.generation;

final class RemoteAudioNative {
    static { System.loadLibrary("lyricsremoteaudio"); }
    static native boolean start(String path, long epoch);
    static native boolean ready();
    static native boolean arm(long epoch, int handle);
    static native int status(long epoch);
    static native byte[] decrypt(long epoch, byte[] input);
    static native void clear();
    static native void stop();
    private RemoteAudioNative() { }
}
