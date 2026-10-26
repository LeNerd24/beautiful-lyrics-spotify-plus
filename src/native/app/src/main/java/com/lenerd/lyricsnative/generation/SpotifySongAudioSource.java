package com.lenerd.lyricsnative.generation;

import android.util.Log;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class SpotifySongAudioSource implements AutoCloseable {
    private final SpotifyPlusContext context;
    private final SpotifyCachedAudioSource cached;
    private final SpotifyRemoteAudioSource remote;
    private final AtomicReference<Ticket> accepted = new AtomicReference<>();

    static final class Ticket {
        final String trackId, generation;
        final SpotifyCachedAudioSource.Ticket initial;
        volatile SpotifyCachedAudioSource.Ticket current;
        final AtomicBoolean started = new AtomicBoolean(), ended = new AtomicBoolean();
        Ticket(String trackId, String generation, SpotifyCachedAudioSource.Ticket initial) {
            this.trackId = trackId;
            this.generation = generation;
            this.initial = initial;
            current = initial;
        }
    }

    SpotifySongAudioSource(SpotifyPlusContext context) {
        this.context = context;
        cached = new SpotifyCachedAudioSource(context);
        remote = new SpotifyRemoteAudioSource(context);
    }

    Ticket begin(String trackId, String generation) throws IOException {
        Ticket ticket = new Ticket(trackId, generation, cached.begin(trackId, generation));
        Ticket previous = accepted.getAndSet(ticket);
        if (previous != null) finish(previous);
        return ticket;
    }

    private boolean current(Ticket ticket) { return ticket != null && accepted.get() == ticket && !ticket.ended.get() && cached.samePlayback(ticket.initial); }

    void writeCurrentTrack(Ticket ticket, File target, Consumer<String> progress) throws IOException {
        if (!current(ticket) || !ticket.started.compareAndSet(false, true)) throw new InterruptedIOException("Spotify audio request is no longer current");
        try {
            if (localPlayback()) {
                progress.accept("Reading song");
                try {
                    cached.writeCurrentTrack(ticket.current, target, 8000);
                    return;
                } catch (IOException error) {
                    if (Thread.currentThread().isInterrupted() || !current(ticket)) throw error;
                    Log.i("LyricsSpotifyAudio", "Current song cache unavailable; using Spotify download");
                    ticket.current = cached.begin(ticket.trackId, ticket.generation);
                    if (!current(ticket)) throw new InterruptedIOException("Spotify playback changed before audio fallback");
                }
            } else Log.i("LyricsSpotifyAudio", "Spotify Connect playback; using Spotify download");
            remote.writeCurrentTrack(ticket, "spotify:track:" + ticket.trackId, target, () -> current(ticket) && cached.eligible(ticket.current), progress);
        } finally {
            finish(ticket);
        }
    }

    private boolean localPlayback() throws IOException {
        byte[] bytes = null;
        try {
            Object core = Class.forName("com.lenerd.spotifyplus.module.hooks.PlayerHook", false, getClass().getClassLoader()).getField("core").get(null);
            Object client = core.getClass().getField("a").get(core);
            Object request = SpotifyReflection.call(context.getHooks().findClass("com.spotify.connect.esperanto.proto.ConnectMessages$StateRequest"), "s");
            SpotifyReflection.call(request, "q");
            Object stream = SpotifyReflection.call(client, "callStream", "spotify.connect.esperanto.proto.ConnectService", "State", SpotifyReflection.call(request, "build"));
            bytes = (byte[]) SpotifyReflection.call(SpotifyReflection.call(SpotifyReflection.call(stream, "firstOrError"), "timeout", 5L, TimeUnit.SECONDS), "blockingGet");
            Object state = SpotifyReflection.call(context.getHooks().findClass("com.spotify.connect.esperanto.proto.ConnectMessages$StateResponse"), "t", bytes);
            return !Boolean.TRUE.equals(SpotifyReflection.call(state, "r")) || Boolean.TRUE.equals(SpotifyReflection.call(state, "q"));
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Spotify audio request was cancelled");
            return true;
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    void finish(Ticket ticket) {
        if (ticket == null) return;
        ticket.ended.set(true);
        accepted.compareAndSet(ticket, null);
        remote.cancel(ticket);
        cached.finish(ticket.current);
    }

    @Override
    public void close() {
        finish(accepted.get());
        remote.close();
        cached.close();
    }
}
