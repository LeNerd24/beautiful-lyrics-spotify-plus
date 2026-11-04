package com.lenerd.lyricsnative.sing;

import android.content.Context;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class QnnRuntime {
    private static File installed;
    interface Assets {
        byte[] read(String path) throws Exception;
    }

    private QnnRuntime() { }

    static void requireDspAccess() {
        System.loadLibrary("lyricsqnn");
        String error = dspError();
        if (error != null) throw new IllegalStateException("Qualcomm DSP library unavailable: " + error + ". The host APK must declare <uses-native-library android:name=\"libcdsprpc.so\" android:required=\"false\" /> inside <application>");
    }

    private static native String dspError();

    public static synchronized File install(SpotifyPlusContext context) throws Exception {
        if (installed != null) return installed;
        var activity = context.getActivity();
        if (activity == null) throw new IOException("Spotify activity unavailable for QNN installation");
        installed = install(activity.getApplicationContext(), path -> context.getApi().Assets.readBytes("assets/native/qnn/" + path).get(30, TimeUnit.SECONDS));
        return installed;
    }

    public static synchronized void unload() {
        release(installed);
        installed = null;
    }

    static synchronized File install(Context context, Assets assets) throws Exception {
        JSONObject manifest = new JSONObject(new String(assets.read("runtime.json"), java.nio.charset.StandardCharsets.UTF_8));
        if (!manifest.getString("version").equals("2.37.1")) throw new IOException("Unexpected QNN runtime version");
        File root = new File(context.getCodeCacheDir(), "lyrics-qnn-2.37.1");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create QNN cache");
        var libraries = manifest.getJSONArray("libraries");
        ArrayList<File> installed = new ArrayList<>();
        for (int i = 0; i < libraries.length(); i++) {
            var library = libraries.getJSONObject(i);
            String name = library.getString("name"), hash = library.getString("sha256");
            long size = library.getLong("size");
            if (!name.matches("libQnn(Htp[A-Za-z0-9]*|System|Gpu)\\.so") || !hash.matches("[a-f0-9]{64}") || size <= 0 || size > 80L * 1024 * 1024) throw new IOException("Invalid QNN library metadata");
            File target = new File(root, hash + "-" + name);
            if (!verified(target, size, hash)) {
                var parts = library.getJSONArray("parts");
                if (parts.length() == 0 || parts.length() > 20) throw new IOException("Invalid QNN library parts");
                var input = new ArrayList<java.io.InputStream>();
                for (int part = 0; part < parts.length(); part++) {
                    var metadata = parts.getJSONObject(part);
                    String path = metadata.getString("path");
                    if (!path.equals(name + ".gz." + part)) throw new IOException("Invalid QNN asset path");
                    byte[] data = assets.read(path);
                    if (data.length > 4 * 1024 * 1024 || data.length != metadata.getInt("size") || !digest(data).equals(metadata.getString("sha256"))) throw new IOException("QNN asset checksum failed: " + path);
                    input.add(new ByteArrayInputStream(data));
                }
                File temporary = new File(root, UUID.randomUUID() + ".tmp");
                try {
                    try (var gzip = new GZIPInputStream(new java.io.SequenceInputStream(Collections.enumeration(input))); var output = new FileOutputStream(temporary)) {
                        byte[] block = new byte[65536];
                        long written = 0;
                        for (int count; (count = gzip.read(block)) != -1;) {
                            written += count;
                            if (written > size) throw new IOException("QNN library exceeds declared size");
                            output.write(block, 0, count);
                        }
                    }
                    if (!verified(temporary, size, hash)) throw new IOException("QNN library checksum failed: " + name);
                    if (!temporary.setReadOnly()) throw new IOException("Cannot protect QNN library");
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary.toPath());
                }
            }
            installed.add(target);
        }
        File session = new File(root, "session-" + UUID.randomUUID());
        if (!session.mkdir()) throw new IOException("Cannot create QNN session directory");
        try {
            for (int i = 0; i < installed.size(); i++) {
                File library = new File(session, libraries.getJSONObject(i).getString("name"));
                Files.copy(installed.get(i).toPath(), library.toPath());
                if (!library.setReadOnly()) throw new IOException("Cannot protect QNN session library");
            }
            return session;
        } catch (Exception error) {
            release(session);
            throw error;
        }
    }

    static void release(File directory) {
        if (directory == null || !directory.getName().startsWith("session-") || directory.getParentFile() == null || !directory.getParentFile().getName().equals("lyrics-qnn-2.37.1")) return;
        try {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) Files.deleteIfExists(file.toPath());
            }
            Files.deleteIfExists(directory.toPath());
        } catch (IOException error) {
            android.util.Log.w("SpotifySing", "Could not remove QNN session files", error);
        }
    }

    static void load(File directory) {
        for (String name : new String[] {"libQnnSystem.so", "libQnnHtpPrepare.so", "libQnnHtpV68Stub.so", "libQnnHtpV69Stub.so", "libQnnHtpV73Stub.so", "libQnnHtpV75Stub.so", "libQnnHtpV79Stub.so", "libQnnHtp.so"}) System.load(new File(directory, name).getAbsolutePath());
    }

    public static void configure(ai.onnxruntime.OrtSession.SessionOptions options, File directory, String backend, String performance) throws ai.onnxruntime.OrtException {
        if (backend.equals("htp")) {
            requireDspAccess();
            load(directory);
            try {
                String existing = android.system.Os.getenv("ADSP_LIBRARY_PATH");
                if (existing == null || !existing.startsWith(directory.getAbsolutePath() + ";")) android.system.Os.setenv("ADSP_LIBRARY_PATH", directory.getAbsolutePath() + ";" + (existing == null ? "/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp" : existing), true);
            } catch (android.system.ErrnoException error) {
                throw new IllegalStateException("Cannot configure Qualcomm DSP library path", error);
            }
            options.addQnn(java.util.Map.of("backend_path", new File(directory, "libQnnHtp.so").getAbsolutePath(), "htp_performance_mode", performance, "enable_htp_fp16_precision", "1"));
        } else if (backend.equals("gpu")) {
            System.load(new File(directory, "libQnnGpu.so").getAbsolutePath());
            options.addQnn(java.util.Map.of("backend_path", new File(directory, "libQnnGpu.so").getAbsolutePath()));
        } else {
            throw new IllegalArgumentException("Unknown Qualcomm backend: " + backend);
        }
    }

    private static boolean verified(File file, long size, String hash) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] block = new byte[65536];
            for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
        }
        return hex(digest.digest()).equals(hash);
    }

    private static String digest(byte[] data) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static String hex(byte[] data) {
        StringBuilder value = new StringBuilder(data.length * 2);
        for (byte element : data) value.append(String.format(java.util.Locale.ROOT, "%02x", element & 255));
        return value.toString();
    }
}
