package com.lenerd.lyricsnative.generation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.net.ssl.HttpsURLConnection;

final class RemoteAudioDownload implements AutoCloseable {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final AtomicReference<HttpsURLConnection> active = new AtomicReference<>();
    private final LongSupplier currentEpoch;
    private final long epoch;
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    private volatile boolean closed;

    RemoteAudioDownload(LongSupplier currentEpoch, long epoch) {
        this.currentEpoch = currentEpoch;
        this.epoch = epoch;
    }

    byte[] read(List<URI> urls) throws IOException {
        if (urls == null || urls.isEmpty()) throw new IOException("Audio URL unavailable");
        for (int attempt = 0; attempt < Math.min(urls.size(), 3); attempt++) {
            requireCurrent();
            try {
                return readOne(urls.get(attempt));
            } catch (IOException error) {
                requireCurrent();
                if (attempt == Math.min(urls.size(), 3) - 1) throw new IOException("Spotify audio download failed");
            }
        }
        throw new IOException("Spotify audio download failed");
    }

    private byte[] readOne(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) throw new IOException("Unsupported audio URL");
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        if (!active.compareAndSet(null, connection)) {
            connection.disconnect();
            throw new IOException("Audio download already active");
        }
        byte[] buffer = null;
        try {
            requireCurrent();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (connection.getResponseCode() != 200) throw new IOException("Audio response rejected");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("Encoded audio response rejected");
            long declared = connection.getContentLengthLong();
            if (declared == 0 || declared > MAX_BYTES) throw new IOException("Audio exceeds limit");
            buffer = new byte[MAX_BYTES + 1];
            int used = 0;
            try (InputStream input = connection.getInputStream()) {
                while (used < buffer.length) {
                    requireCurrent();
                    int count = input.read(buffer, used, Math.min(65536, buffer.length - used));
                    if (count == -1) break;
                    if (count == 0) continue;
                    used += count;
                }
            }
            requireCurrent();
            if (used == 0 || used > MAX_BYTES || declared >= 0 && used != declared) throw new IOException("Incomplete audio response");
            return Arrays.copyOf(buffer, used);
        } finally {
            if (buffer != null) Arrays.fill(buffer, (byte) 0);
            active.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    private void requireCurrent() throws IOException {
        if (closed || Thread.currentThread().isInterrupted() || epoch == 0 || currentEpoch.getAsLong() != epoch || System.nanoTime() >= deadline) throw new InterruptedIOException("Audio download cancelled");
    }

    @Override
    public void close() {
        closed = true;
        HttpsURLConnection connection = active.getAndSet(null);
        if (connection != null) connection.disconnect();
    }
}
