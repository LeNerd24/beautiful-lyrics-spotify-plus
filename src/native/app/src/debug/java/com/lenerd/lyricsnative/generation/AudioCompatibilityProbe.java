package com.lenerd.lyricsnative.generation;

import android.util.Log;

import com.lenerd.spotifyplus.sdk.SpotifyPlusPlugin;
import com.lenerd.spotifyplus.sdk.SpotifyPlusRegistry;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AudioCompatibilityProbe implements SpotifyPlusPlugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile SpotifySongAudioSource source;

    @Override
    public void register(SpotifyPlusRegistry registry, SpotifyPlusContext context) {
        worker.execute(() -> {
            try (SpotifySongAudioSource audio = new SpotifySongAudioSource(context)) {
                source = audio;
                for (int attempt = 1; attempt <= 2; attempt++) {
                    File directory = Files.createTempDirectory(context.getActivity().getCacheDir().toPath(), "lyrics-audio-probe-").toFile();
                    File recording = new File(directory, "recording"), pcm = new File(directory, "decoded.pcm");
                    SpotifySongAudioSource.Ticket ticket = null;
                    try {
                        String trackId = context.getPlayer().getCurrentTrack().id;
                        ticket = audio.begin(trackId, UUID.randomUUID().toString());
                        Log.i("LyricsAudioCompatibility", "BEGIN attempt=" + attempt);
                        audio.writeCurrentTrack(ticket, recording, message -> Log.i("LyricsAudioCompatibility", message));
                        AudioCodec.Decoded decoded = AudioCodec.decodeToPcm(recording, pcm);
                        if (decoded.frames() <= 0 || decoded.rate() <= 0) throw new IllegalStateException("Empty decoded audio");
                        Log.i("LyricsAudioCompatibility", "PASS attempt=" + attempt + " bytes=" + recording.length() + " frames=" + decoded.frames() + " rate=" + decoded.rate());
                    } finally {
                        audio.finish(ticket);
                        Files.deleteIfExists(recording.toPath());
                        Files.deleteIfExists(pcm.toPath());
                        Files.deleteIfExists(directory.toPath());
                    }
                }
            } catch (Throwable error) {
                Log.e("LyricsAudioCompatibility", "FAIL", error);
            } finally {
                source = null;
            }
        });
    }

    @Override
    public void onUnload() {
        worker.shutdownNow();
        SpotifySongAudioSource current = source;
        if (current != null) current.close();
    }
}
