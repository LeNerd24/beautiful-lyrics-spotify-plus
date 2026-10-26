package com.lenerd.lyricsnative.generation;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.lenerd.spotifyplus.sdk.SpotifyPlusEvents;
import com.lenerd.spotifyplus.sdk.hooks.HookHandle;
import com.lenerd.spotifyplus.sdk.hooks.SpotifyPlusHooks;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

final class SpotifyCachedAudioSource implements AutoCloseable {
    private static final String SPOTIFY_VERSION = "9.1.82.2160";
    private static final int MAX_AUDIO_BYTES = 16 * 1024 * 1024;
    private static final long WINDOW_MS = 60_000;
    private static final ReentrantLock SESSION_LOCK = new ReentrantLock();
    private final SpotifyPlusContext context;
    private final ArrayList<HookHandle> hooks = new ArrayList<>();
    private final AtomicReference<Ticket> accepted = new AtomicReference<>();
    private final Consumer<Object> changed = ignored -> cancelCurrent();
    private SpotifyPlusEvents events;
    private Field stateField;
    private Method track, present, unwrap, uri, uid, playback, session, playing, paused;
    private volatile boolean closed, scopeClosing;
    private String unavailable;

    private record Identity(String uri, String uid, String playback, String session) { }

    static final class Ticket {
        private final SpotifyCachedAudioSource owner;
        private final String trackId, generation;
        private final Identity identity;
        private final byte[] gid;
        private final AtomicBoolean ended = new AtomicBoolean(), started = new AtomicBoolean();
        private final AtomicLong epoch = new AtomicLong();
        private volatile long deadline;

        private Ticket(SpotifyCachedAudioSource owner, String trackId, String generation, Identity identity, byte[] gid) {
            this.owner = owner;
            this.trackId = trackId;
            this.generation = generation;
            this.identity = identity;
            this.gid = gid;
        }
    }

    SpotifyCachedAudioSource(SpotifyPlusContext context) {
        this.context = context;
        try {
            if (context == null) throw new IOException("Spotify context is unavailable");
            SpotifyPlusHooks api = context.getHooks();
            stateField = Class.forName("com.lenerd.spotifyplus.module.Utils", false, getClass().getClassLoader()).getField("playerState");
            Class<?> state = api.findClass("com.spotify.player.model.PlayerState"), item = api.findClass("com.spotify.player.model.ContextTrack");
            track = state.getMethod("track");
            present = track.getReturnType().getMethod("c");
            unwrap = track.getReturnType().getMethod("b");
            uri = item.getMethod("uri");
            uid = item.getMethod("uid");
            playback = state.getMethod("playbackId");
            session = state.getMethod("sessionId");
            playing = state.getMethod("isPlaying");
            paused = state.getMethod("isPaused");
            for (String name : new String[] {"com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl", "com.spotify.core.coreimpl.NativeApplicationScopeImpl", "com.spotify.concurrency.asyncimpl.NativeTimerManagerThreadImpl"}) {
                int installed = 0;
                for (Method method : api.findClass(name).getDeclaredMethods()) {
                    if (!Set.of("prepareForShutdown", "destroy", "stop", "kill").contains(method.getName())) continue;
                    hooks.add(Objects.requireNonNull(api.before(method, call -> { scopeClosing = true; cancelCurrent(); })));
                    installed++;
                }
                if (installed == 0) throw new IOException("Spotify cache lifecycle guard is unavailable");
            }
            events = context.getEvents();
            events.on("songChanged", changed);
        } catch (Throwable error) {
            unavailable = error instanceof IOException ? error.getMessage() : "Spotify cached audio is unavailable (" + error.getClass().getSimpleName() + ")";
            removeHooks();
        }
    }

    Ticket begin(String trackId, String generation) throws IOException {
        if (unavailable != null) throw new IOException(unavailable);
        if (closed || scopeClosing || trackId == null || !trackId.matches("[A-Za-z0-9]{22}") || generation == null || generation.isEmpty()) throw new IOException("Spotify cached audio request is unavailable");
        try {
            Activity activity = context.getActivity();
            if (activity == null || !SPOTIFY_VERSION.equals(activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName)) throw new IOException("Spotify " + SPOTIFY_VERSION + " is required for cached audio");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IOException("Spotify version is unavailable", error);
        }
        Identity current = identity();
        if (current == null || !("spotify:track:" + trackId).equals(current.uri())) throw new IOException("Play the requested song before generating lyrics");
        Ticket ticket = new Ticket(this, trackId, generation, current, trackGid(trackId));
        Ticket previous = accepted.getAndSet(ticket);
        if (previous != null) finish(previous);
        if (!eligible(ticket)) {
            finish(ticket);
            throw new InterruptedIOException("Spotify playback changed before generation started");
        }
        return ticket;
    }

    void writeCurrentTrack(Ticket ticket, File target) throws IOException { writeCurrentTrack(ticket, target, WINDOW_MS); }

    void writeCurrentTrack(Ticket ticket, File target, long waitMs) throws IOException {
        if (waitMs <= 0 || waitMs > WINDOW_MS) throw new IOException("Invalid Spotify cache wait");
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Read Spotify cached audio on a worker thread");
        require(ticket);
        if (target == null || !ticket.started.compareAndSet(false, true)) throw new IOException("Spotify cached audio request was already consumed");
        boolean locked = false, nativeAttempted = false, published = false, complete = false;
        byte[] clear = null;
        File temporary = null;
        File library = null;
        try {
            SESSION_LOCK.lockInterruptibly();
            locked = true;
            require(ticket);
            library = SpotifyAudioRuntime.install(context);
            require(ticket);
            nativeAttempted = true;
            if (!SpotifyAudioNative.start(library.getAbsolutePath())) throw new IOException("Spotify cached audio observer could not start");
            SpotifyAudioRuntime.activate(library);
            long epoch = SpotifyAudioNative.epoch();
            ticket.deadline = SystemClock.elapsedRealtime() + waitMs;
            ticket.epoch.set(epoch);
            if (epoch == 0 || (epoch & 1) == 0) throw new IOException("Spotify cached audio observer is inactive");
            require(ticket);
            boolean armed = false;
            while (true) {
                require(ticket);
                if (!armed) {
                    byte[] captured = SpotifyAudioNative.resources();
                    byte[] fileId = null;
                    try {
                        require(ticket);
                        fileId = matchingFile(captured, ticket.gid);
                        if (fileId != null) {
                            require(ticket);
                            if (!SpotifyAudioNative.armCache(epoch, fileId, ticket.gid)) throw new IOException("Spotify cache request was rejected");
                            require(ticket);
                            armed = true;
                            Log.i("LyricsSpotifyAudio", "Current recording matched and armed");
                        }
                    } finally {
                        if (captured != null) Arrays.fill(captured, (byte) 0);
                        if (fileId != null) Arrays.fill(fileId, (byte) 0);
                    }
                }
                if (armed && SpotifyAudioNative.cacheFileReady(epoch) && SpotifyAudioNative.cacheCipherReady(epoch)) break;
                Thread.sleep(100);
            }
            require(ticket);
            clear = SpotifyAudioNative.decodedCache(epoch);
            require(ticket);
            if (clear == null || clear.length == 0 || clear.length > MAX_AUDIO_BYTES) throw new IOException("Spotify cached song could not be read completely");
            int offset = AudioContainer.start(clear);
            Log.i("LyricsSpotifyAudio", "Verified cached recording: bytes=" + clear.length + "; containerOffset=" + offset);
            File parent = target.getAbsoluteFile().getParentFile();
            if (parent == null || !parent.isDirectory()) throw new IOException("Lyrics audio directory is unavailable");
            temporary = File.createTempFile("lyrics-current-", ".audio", parent);
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------"));
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                for (int position = offset; position < clear.length;) {
                    require(ticket);
                    int length = Math.min(65536, clear.length - position);
                    output.write(clear, position, length);
                    position += length;
                }
                require(ticket);
                output.getFD().sync();
            }
            require(ticket);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            require(ticket);
            temporary = null;
            complete = true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Spotify cached audio was cancelled");
        } catch (RuntimeException | LinkageError error) {
            throw new IOException("Spotify cached audio could not be prepared", error);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
            try {
                if (temporary != null) Files.deleteIfExists(temporary.toPath());
                if (published && !complete) Files.deleteIfExists(target.toPath());
            } finally {
                finish(ticket);
                if (nativeAttempted) {
                    try {
                        SpotifyAudioNative.stop();
                    } catch (Throwable ignored) { }
                }
                SpotifyAudioRuntime.release(library);
                if (locked) SESSION_LOCK.unlock();
                Arrays.fill(ticket.gid, (byte) 0);
            }
        }
    }

    void finish(Ticket ticket) {
        if (ticket == null || ticket.owner != this) return;
        ticket.ended.set(true);
        accepted.compareAndSet(ticket, null);
        long epoch = ticket.epoch.get();
        if (epoch != 0 && (epoch & 1) != 0) {
            try {
                SpotifyAudioNative.deactivateEpoch(epoch);
            } catch (Throwable ignored) { }
        }
        if (!ticket.started.get()) Arrays.fill(ticket.gid, (byte) 0);
    }

    private void cancelCurrent() { finish(accepted.get()); }

    boolean samePlayback(Ticket ticket) { return ticket != null && ticket.owner == this && !closed && !scopeClosing && ticket.identity.equals(identity()); }

    boolean eligible(Ticket ticket) {
        if (ticket == null || ticket.owner != this || closed || scopeClosing || ticket.ended.get() || accepted.get() != ticket || !ticket.identity.equals(identity())) return false;
        long epoch = ticket.epoch.get();
        return epoch == 0 || (SystemClock.elapsedRealtime() < ticket.deadline && SpotifyAudioNative.epoch() == epoch);
    }

    private void require(Ticket ticket) throws IOException {
        if (Thread.currentThread().isInterrupted() || !eligible(ticket)) {
            finish(ticket);
            throw new InterruptedIOException("Spotify playback changed or the cached audio request expired");
        }
    }

    private Identity identity() {
        try {
            Object state = stateField.get(null);
            if (state == null || !Boolean.TRUE.equals(playing.invoke(state)) || Boolean.TRUE.equals(paused.invoke(state))) return null;
            Object optional = track.invoke(state);
            if (!Boolean.TRUE.equals(present.invoke(optional))) return null;
            Object item = unwrap.invoke(optional), playbackId = playback.invoke(state);
            String id = Boolean.TRUE.equals(present.invoke(playbackId)) ? String.valueOf(unwrap.invoke(playbackId)) : "";
            Identity result = new Identity((String) uri.invoke(item), (String) uid.invoke(item), id, (String) session.invoke(state));
            return result.playback().isEmpty() || result.session() == null || result.session().isEmpty() ? null : result;
        } catch (Throwable error) {
            return null;
        }
    }

    static byte[] trackGid(String trackId) throws IOException {
        if (trackId == null || !trackId.matches("[A-Za-z0-9]{22}")) throw new IOException("Invalid Spotify track identifier");
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        BigInteger gid = BigInteger.ZERO;
        for (char value : trackId.toCharArray()) gid = gid.multiply(BigInteger.valueOf(62)).add(BigInteger.valueOf(alphabet.indexOf(value)));
        if (gid.bitLength() > 128) throw new IOException("Spotify track identifier exceeds its limit");
        byte[] encoded = gid.toByteArray(), result = new byte[16];
        int size = Math.min(encoded.length, result.length);
        System.arraycopy(encoded, encoded.length - size, result, result.length - size, size);
        Arrays.fill(encoded, (byte) 0);
        return result;
    }

    static byte[] matchingFile(byte[] captured, byte[] currentGid) throws IOException {
        if (captured == null || captured.length > 1280 || captured.length % 40 != 0 || currentGid == null || currentGid.length != 16) throw new IOException("Spotify resource metadata is invalid");
        for (int offset = 0; offset < captured.length; offset += 40) {
            boolean matches = true;
            for (int index = 0; index < 16; index++)
                if (captured[offset + 24 + index] != currentGid[index]) {
                    matches = false;
                    break;
                }
            if (matches) return Arrays.copyOfRange(captured, offset + 4, offset + 24);
        }
        return null;
    }

    private void removeHooks() {
        if (events != null) {
            try {
                events.off("songChanged", changed);
            } catch (Throwable ignored) { }
        }
        for (HookHandle hook : hooks) {
            try {
                hook.close();
            } catch (Throwable ignored) { }
        }
        hooks.clear();
    }

    @Override
    public void close() {
        closed = true;
        cancelCurrent();
        removeHooks();
    }
}
