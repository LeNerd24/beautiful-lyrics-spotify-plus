package com.lenerd.lyricsnative.generation;

import android.os.SystemClock;
import android.util.Log;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class SpotifyRemoteAudioSource implements AutoCloseable {
    private final SpotifyPlusContext context;
    private final AtomicLong epochs = new AtomicLong(1);
    private final AtomicReference<Job> active = new AtomicReference<>();
    private volatile boolean closed;
    private Job runtimeOwner;

    private static final class Job {
        final Object token;
        final BooleanSupplier eligible;
        final long epoch, deadline = SystemClock.elapsedRealtime() + 180000;
        volatile boolean cancelled;
        volatile RemoteScopeLease lease;
        volatile RemoteSongHandleCall handle;
        volatile SpotifyResolverCall resolver;
        volatile RemoteAudioDownload download;
        Job(Object token, BooleanSupplier eligible, long epoch) {
            this.token = token;
            this.eligible = eligible;
            this.epoch = epoch;
        }
    }

    SpotifyRemoteAudioSource(SpotifyPlusContext context) { this.context = context; }
    private long epoch(Job job) { return !closed && !job.cancelled && active.get() == job && (job.lease == null || job.lease.alive()) && SystemClock.elapsedRealtime() < job.deadline && job.eligible.getAsBoolean() ? job.epoch : 0; }
    private void require(Job job) throws IOException {
        if (Thread.currentThread().isInterrupted() || epoch(job) == 0) throw new InterruptedIOException("Spotify audio request was cancelled or expired");
    }

    void writeCurrentTrack(Object token, String uri, File target, BooleanSupplier eligible, Consumer<String> progress) throws IOException {
        Job job = new Job(token, eligible, epochs.getAndAdd(2));
        if (closed || !active.compareAndSet(null, job)) throw new IOException("Spotify audio request is already active");
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        File library = null, temporary = null;
        byte[] fileId = null, encrypted = null, clear = null;
        boolean published = false, complete = false;
        String stage = "metadata";
        try (RemoteScopeLease lease = new RemoteScopeLease(context)) {
            job.lease = lease;
            monitor.scheduleWithFixedDelay(() -> { if (epoch(job) == 0) cancel(job); }, 200, 200, TimeUnit.MILLISECONDS);
            require(job);
            progress.accept("Preparing song");
            try (AudioFilesPayload.Selection selected = RemoteAudioFileLookup.fetch(context, uri)) {
                require(job);
                fileId = selected.fileId();
                stage = "runtime";
                library = RemoteAudioRuntime.install(context);
                require(job);
                synchronized (this) {
                    require(job);
                    runtimeOwner = job;
                    if (!RemoteAudioNative.start(library.getAbsolutePath(), job.epoch)) throw new IOException("Spotify audio decoder could not start");
                }
                SpotifyAudioRuntime.activate(library);
                for (int i = 0; i < 50 && !RemoteAudioNative.ready(); i++) {
                    require(job);
                    Thread.sleep(100);
                }
                if (!RemoteAudioNative.ready()) throw new IOException("Spotify audio decoder setup timed out");
                stage = "initialization";
                int handle;
                try (RemoteSongHandleCall call = new RemoteSongHandleCall(() -> epoch(job), job.epoch)) {
                    job.handle = call;
                    handle = call.execute(context, fileId);
                } finally {
                    job.handle = null;
                }
                require(job);
                lease.capture(handle, job.epoch);
                require(job);
                stage = "resolution";
                Object maker = Class.forName("com.lenerd.spotifyplus.module.hooks.ServicesHook", false, getClass().getClassLoader()).getField("retrofit").get(null);
                StorageResolution.Response resolved;
                try (StorageResolution.Request request = new StorageResolution.Request(selected.format, fileId, job.epoch); SpotifyResolverCall call = new SpotifyResolverCall(maker, request, () -> epoch(job), context.getHooks())) {
                    job.resolver = call;
                    resolved = call.executeOnWorker();
                } finally {
                    job.resolver = null;
                }
                require(job);
                stage = "download";
                progress.accept("Downloading song");
                try (RemoteAudioDownload download = new RemoteAudioDownload(() -> epoch(job), job.epoch)) {
                    job.download = download;
                    encrypted = download.read(resolved.urls(job.epoch));
                } finally {
                    job.download = null;
                }
                require(job);
                stage = "decryption";
                clear = lease.decrypt(job.epoch, encrypted);
                require(job);
                int offset = AudioContainer.start(clear);
                Log.i("LyricsSpotifyAudio", "Verified downloaded recording: bytes=" + clear.length + "; containerOffset=" + offset);
                File parent = target.getAbsoluteFile().getParentFile();
                if (parent == null || !parent.isDirectory()) throw new IOException("Lyrics audio directory is unavailable");
                stage = "publication";
                temporary = File.createTempFile("lyrics-current-", ".audio", parent);
                Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------"));
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    for (int position = offset; position < clear.length;) {
                        require(job);
                        int count = Math.min(65536, clear.length - position);
                        output.write(clear, position, count);
                        position += count;
                    }
                    require(job);
                    output.getFD().sync();
                }
                require(job);
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                published = true;
                temporary = null;
                require(job);
                complete = true;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Spotify audio request was cancelled");
        } catch (Exception | LinkageError error) {
            if (error instanceof InterruptedIOException interrupted) throw interrupted;
            throw new IOException("Could not read the current song from Spotify (" + stage + ")", error);
        } finally {
            monitor.shutdownNow();
            cancel(job);
            if (fileId != null) Arrays.fill(fileId, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
            try {
                if (temporary != null) Files.deleteIfExists(temporary.toPath());
                if (published && !complete) Files.deleteIfExists(target.toPath());
            } finally {
                synchronized (this) {
                    if (runtimeOwner == job) {
                        RemoteAudioNative.stop();
                        runtimeOwner = null;
                    }
                }
                RemoteAudioRuntime.release(library);
                active.compareAndSet(job, null);
            }
        }
    }

    void cancel(Object token) {
        Job job = active.get();
        if (job != null && job.token == token) cancel(job);
    }
    private void cancel(Job job) {
        job.cancelled = true;
        RemoteSongHandleCall handle = job.handle;
        SpotifyResolverCall resolver = job.resolver;
        RemoteAudioDownload download = job.download;
        if (handle != null) handle.close();
        if (resolver != null) resolver.close();
        if (download != null) download.close();
        synchronized (this) {
            if (runtimeOwner == job) RemoteAudioNative.clear();
        }
    }

    @Override
    public void close() {
        closed = true;
        Job job = active.get();
        if (job != null) cancel(job);
    }
}
