package com.lenerd.lyricsnative.generation;

import android.util.Log;

import com.lenerd.spotifyplus.sdk.SpotifyPlusPlugin;
import com.lenerd.spotifyplus.sdk.SpotifyPlusRegistry;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AudioSetupProbe implements SpotifyPlusPlugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    public void register(SpotifyPlusRegistry registry, SpotifyPlusContext context) {
        worker.execute(() -> {
            try (SpotifySongAudioSource source = new SpotifySongAudioSource(context); RemoteScopeLease lease = new RemoteScopeLease(context)) {
                String trackId = context.getPlayer().getCurrentTrack().id;
                SpotifySongAudioSource.Ticket ticket = source.begin(trackId, UUID.randomUUID().toString());
                try {
                    Log.i("LyricsAudioCompatibility", "PASS playback identity");
                    try (AudioFilesPayload.Selection selection = RemoteAudioFileLookup.fetch(context, "spotify:track:" + trackId)) {
                        if (selection.fileId().length != 20) throw new IllegalStateException("Invalid recording identifier");
                        Log.i("LyricsAudioCompatibility", "PASS recording metadata");
                    }
                    lease.verifyFactory();
                    Log.i("LyricsAudioCompatibility", "PASS authenticated audio factory");
                } finally {
                    source.finish(ticket);
                }
            } catch (Throwable error) {
                Log.e("LyricsAudioCompatibility", "FAIL setup", error);
            }
        });
    }

    @Override
    public void onUnload() { worker.shutdownNow(); }
}
