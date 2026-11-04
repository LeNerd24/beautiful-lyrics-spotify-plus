package com.lenerd.lyricsnative.generation;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

final class SpotifyAudioRuntime {
    private static final String GADGET_ASSET = "assets/native/frida-gadget-17.23.2-android-arm64.so.gz";
    private static final String SCRIPT_ASSET = "assets/native/spotify-cache-reader.js";
    private static final String RESOLVER_ASSET = "assets/native/spotify-audio-resolver.js";
    private static final String GADGET_SHA256 = "73a83a2d33c19d86cb61e11e1ddc7250ee2f79b0f39579c563bff1258ec0b088";
    private static final long GADGET_BYTES = 25_237_232, MAX_LIBRARY_BYTES = 32L * 1024 * 1024, MAX_COMPRESSED_BYTES = 16L * 1024 * 1024, TIMEOUT_SECONDS = 30;
    private static final int MAX_SCRIPT_BYTES = 4 * 1024 * 1024;
    private static final ReentrantLock INSTALL_LOCK = new ReentrantLock();
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        final SpotifyAudioRuntimeLock lock;
        final byte[] source;
        final File script, started, released;
        boolean activated;
        Session(SpotifyAudioRuntimeLock lock, byte[] source, File directory) {
            this.lock = lock;
            this.source = source;
            script = new File(directory, "spotify-cache-reader.js");
            String token = UUID.randomUUID().toString();
            started = new File(directory, "started-" + token);
            released = new File(directory, "released-" + token);
        }
    }

    private SpotifyAudioRuntime() { }

    static File install(SpotifyPlusContext context) throws IOException { return install(context, SCRIPT_ASSET); }

    static File install(SpotifyPlusContext context, String scriptAsset) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Install the Spotify audio runtime on a worker thread");
        if (context == null) throw new IOException("Spotify context is unavailable");
        Activity activity = context.getActivity();
        Context application = activity == null ? null : activity.getApplicationContext();
        if (application == null) throw new IOException("Spotify activity is unavailable");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        boolean locked = false;
        File sessionLibrary = null;
        SpotifyAudioRuntimeLock runtimeLock = null;
        try {
            locked = INSTALL_LOCK.tryLock(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!locked) throw new IOException("Spotify audio runtime installation timed out");
            File root = privateDirectory(application.getCodeCacheDir().getCanonicalFile(), "lyrics-audio");
            runtimeLock = SpotifyAudioRuntimeLock.acquire(new File(root, "runtime.lock").toPath(), deadline);
            File directory = privateDirectory(root, "frida-17.23.2-arm64");
            File library = new File(directory, "liblyricsaudio-gadget.so");

            Object scriptValue = await(context.getApi().Assets.readText(scriptAsset), deadline);
            if (!(scriptValue instanceof String text) || text.trim().isEmpty()) throw new IOException("Spotify audio script asset is unavailable");
            if (scriptAsset.equals("assets/native/spotify-remote-reader.js")) {
                Object resolver = await(context.getApi().Assets.readText(RESOLVER_ASSET), deadline);
                if (!(resolver instanceof String source) || source.trim().isEmpty()) throw new IOException("Spotify audio resolver asset is unavailable");
                text = source + "\n" + text;
            }
            byte[] scriptBytes = text.getBytes(StandardCharsets.UTF_8);
            if (scriptBytes.length > MAX_SCRIPT_BYTES) throw new IOException("Spotify audio script exceeds its size limit");
            if (!verified(library, GADGET_BYTES, GADGET_SHA256)) {
                byte[] compressed = await(context.getApi().Assets.readBytes(GADGET_ASSET), deadline);
                if (compressed == null || compressed.length > MAX_COMPRESSED_BYTES) throw new IOException("Spotify audio runtime asset exceeds its size limit");
                installCompressed(new ByteArrayInputStream(compressed), library, GADGET_BYTES, GADGET_SHA256, MAX_LIBRARY_BYTES);
            } else {
                Files.setPosixFilePermissions(library.toPath(), PosixFilePermissions.fromString("r-x------"));
            }
            String property = "com.lenerd.lyricsnative.audio.resident.v1." + android.os.Process.myPid();
            String resident = System.getProperty(property);
            if (resident == null) {
                File sessionDirectory = Files.createTempDirectory(directory.toPath(), "resident-").toFile();
                Files.setPosixFilePermissions(sessionDirectory.toPath(), PosixFilePermissions.fromString("rwx------"));
                sessionLibrary = new File(sessionDirectory, "liblyricsaudio-gadget.so");
                Files.copy(library.toPath(), sessionLibrary.toPath());
                Files.setPosixFilePermissions(sessionLibrary.toPath(), PosixFilePermissions.fromString("r-x------"));
                File script = new File(sessionDirectory, "spotify-cache-reader.js");
                File config = new File(sessionDirectory, "liblyricsaudio-gadget.config.so");
                writeAtomic(script, "rpc.exports = { init() {} };".getBytes(StandardCharsets.UTF_8));
                JSONObject interaction = new JSONObject().put("type", "script").put("path", script.getAbsolutePath()).put("on_change", "reload");
                JSONObject configuration = new JSONObject().put("interaction", interaction).put("runtime", "qjs").put("teardown", "minimal");
                writeAtomic(config, configuration.toString().getBytes(StandardCharsets.UTF_8));
                System.setProperty(property, sessionLibrary.getAbsolutePath());
            } else {
                sessionLibrary = new File(resident);
                File sessionDirectory = sessionLibrary.getParentFile();
                if (sessionDirectory == null || !sessionDirectory.getName().startsWith("resident-") || !directory.equals(sessionDirectory.getParentFile()) || !sessionLibrary.equals(sessionLibrary.getCanonicalFile()) || !verified(sessionLibrary, GADGET_BYTES, GADGET_SHA256)) throw new IOException("Resident Spotify audio runtime is invalid");
            }
            SESSIONS.put(sessionLibrary.getAbsolutePath(), new Session(runtimeLock, scriptBytes, sessionLibrary.getParentFile()));
            runtimeLock = null;
            return sessionLibrary;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Spotify audio runtime installation was interrupted", error);
        } catch (JSONException | RuntimeException error) {
            throw new IOException("Spotify audio runtime installation failed", error);
        } finally {
            if (runtimeLock != null) runtimeLock.close();
            if (locked) INSTALL_LOCK.unlock();
        }
    }

    static void activate(File library) throws IOException, InterruptedException {
        Session session = library == null ? null : SESSIONS.get(library.getAbsolutePath());
        if (session == null || session.activated) throw new IOException("Spotify audio runtime session is unavailable");
        session.activated = true;
        String source = new String(session.source, StandardCharsets.UTF_8);
        writeAtomic(session.script, SpotifyAudioProfile.active(source, session.started.getAbsolutePath()).getBytes(StandardCharsets.UTF_8));
        awaitMarker(session.started, false);
        android.util.Log.i("LyricsSpotifyAudio", "Audio runtime profile activated");
    }

    static void release(File library) {
        Session session = library == null ? null : SESSIONS.get(library.getAbsolutePath());
        if (session == null) return;
        boolean released = !session.activated;
        try {
            if (session.activated) {
                writeAtomic(session.script, SpotifyAudioProfile.inactive(session.released.getAbsolutePath()).getBytes(StandardCharsets.UTF_8));
                awaitMarker(session.released, true);
                released = true;
                android.util.Log.i("LyricsSpotifyAudio", "Audio runtime profile released; runtime retained");
            }
        } catch (IOException | InterruptedException error) {
            android.util.Log.e("LyricsSpotifyAudio", "Audio runtime profile did not release; further reads blocked until restart");
        } finally {
            java.util.Arrays.fill(session.source, (byte) 0);
            if (released) {
                SESSIONS.remove(library.getAbsolutePath(), session);
                for (File marker : new File[] {session.started, session.released}) {
                    try {
                        Files.deleteIfExists(marker.toPath());
                    } catch (IOException ignored) { }
                }
                session.lock.close();
            }
        }
    }

    private static void awaitMarker(File marker, boolean cleanup) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean interrupted = cleanup && Thread.interrupted();
        try {
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS) && marker.length() > 0 && marker.length() <= 8192) {
                    String status = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
                    if (status.equals("ready")) return;
                    if (status.startsWith("error:")) throw new IOException("Spotify audio runtime rejected this build: " + status.substring(6));
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException error) {
                    if (!cleanup) throw error;
                    interrupted = true;
                }
            }
            throw new IOException("Spotify audio runtime profile change timed out");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static File privateDirectory(File parent, String name) throws IOException {
        File directory = new File(parent, name);
        if (!directory.equals(directory.getCanonicalFile())) throw new IOException("Spotify audio runtime directory is redirected");
        Files.createDirectories(directory.toPath());
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private static <T> T await(CompletableFuture<T> future, long deadline) throws IOException, InterruptedException {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException error) {
            future.cancel(true);
            throw new IOException("Spotify audio runtime asset could not be read", error);
        } catch (InterruptedException error) {
            future.cancel(true);
            throw error;
        }
    }

    static boolean verified(File file, long expectedBytes, String expectedSha256) throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() != expectedBytes) return false;
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[32768];
        try (InputStream input = new FileInputStream(file)) {
            for (int count; (count = input.read(buffer)) != -1;) {
                total += count;
                if (total > expectedBytes) return false;
                digest.update(buffer, 0, count);
            }
        }
        return total == expectedBytes && hex(digest.digest()).equals(expectedSha256);
    }

    static void installCompressed(InputStream compressed, File target, long expectedBytes, String expectedSha256, long maximumBytes) throws IOException {
        if (expectedBytes <= 0 || expectedBytes > maximumBytes || maximumBytes > MAX_LIBRARY_BYTES) throw new IOException("Invalid Spotify audio runtime size");
        File temporary = File.createTempFile("lyrics-gadget-", ".tmp", target.getParentFile());
        try {
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------"));
            MessageDigest digest = sha256();
            long total = 0;
            byte[] buffer = new byte[32768];
            try (InputStream input = new GZIPInputStream(compressed); FileOutputStream output = new FileOutputStream(temporary)) {
                for (int count; (count = input.read(buffer)) != -1;) {
                    total += count;
                    if (total > expectedBytes || total > maximumBytes) throw new IOException("Spotify audio runtime exceeds its pinned size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                if (total != expectedBytes || !hex(digest.digest()).equals(expectedSha256)) throw new IOException("Spotify audio runtime integrity check failed");
                output.getFD().sync();
            }
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("r-x------"));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File temporary = File.createTempFile("lyrics-runtime-", ".tmp", target.getParentFile());
        try {
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------"));
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("r--------"));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value & 255) >>> 4, 16));
            output.append(Character.forDigit(value & 15, 16));
        }
        return output.toString();
    }
}
