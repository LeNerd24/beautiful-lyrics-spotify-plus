package com.lenerd.lyricsnative.generation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class ResumableDownload {
    interface Progress {
        void update(long received, long total);
    }

    static final class Failure extends IOException {
        Failure(String message) { super(message); }
    }

    private static final int ATTEMPTS = 6;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-\\d+/(\\d+|\\*)");

    private ResumableDownload() { }

    static void fetch(OkHttpClient client, String url, File output, long maxBytes, boolean audioOnly, String what, BooleanSupplier cancelled, Progress progress, Consumer<String> log) throws IOException, InterruptedException {
        output.delete();
        long total = -1;
        int failures = 0;

        while (true) {
            long have = output.length();
            Request.Builder request = new Request.Builder().url(url);
            if (have > 0) request.header("Range", "bytes=" + have + "-");

            try (Response response = client.newCall(request.build()).execute()) {
                ResponseBody body = response.body();

                if (response.code() == 416 && have > 0 && total == have) return;

                if (!response.isSuccessful() || body == null) {
                    if (response.code() < 500 && response.code() != 408 && response.code() != 429) throw new Failure("Could not download " + what + " (HTTP " + response.code() + ")");
                    throw new IOException("HTTP " + response.code());
                }

                MediaType type = body.contentType();
                if (audioOnly && type != null && ("text".equals(type.type()) || "json".equals(type.subtype()))) throw new Failure("The download link for " + what + " did not return audio");

                boolean append = false;

                if (response.code() == 206) {
                    Matcher range = CONTENT_RANGE.matcher(response.header("Content-Range", ""));

                    if (range.matches() && Long.parseLong(range.group(1)) == have) {
                        append = true;
                        if (!"*".equals(range.group(2))) total = Long.parseLong(range.group(2));
                    }
                } else if (body.contentLength() >= 0) {
                    total = body.contentLength();
                }

                if (!append) have = 0;

                if (total > maxBytes) throw new Failure(what.substring(0, 1).toUpperCase() + what.substring(1) + " is too large");

                long received = have;

                try (InputStream input = body.byteStream(); OutputStream stream = new FileOutputStream(output, append)) {
                    byte[] block = new byte[1 << 16];
                    int count;

                    while ((count = input.read(block)) != -1) {
                        if (cancelled.getAsBoolean()) throw new InterruptedIOException();

                        received += count;
                        if (received > maxBytes) throw new Failure(what.substring(0, 1).toUpperCase() + what.substring(1) + " is too large");

                        stream.write(block, 0, count);
                        progress.update(received, total);

                        failures = 0;
                    }
                }

                if (total < 0 || output.length() >= total) return;
                throw new IOException("Connection closed after " + output.length() + " of " + total + " bytes");
            } catch (Failure error) {
                throw error;
            } catch (IOException error) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw error;
                if (++failures >= ATTEMPTS) throw error;

                log.accept("Downloading " + what + " was interrupted at " + output.length() + " bytes, retrying (" + failures + "/" + (ATTEMPTS - 1) + "): " + error);
                Thread.sleep(1000L << (failures - 1));
            }
        }
    }
}
