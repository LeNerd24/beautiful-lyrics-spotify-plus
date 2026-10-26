package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.File;
import java.io.IOException;

final class RemoteAudioRuntime {
    static File install(SpotifyPlusContext context) throws IOException { return SpotifyAudioRuntime.install(context, "assets/native/spotify-remote-reader.js"); }
    static void release(File library) { SpotifyAudioRuntime.release(library); }
    private RemoteAudioRuntime() { }
}
