package com.lenerd.lyricsnative.generation;

import android.util.Log;

import com.lenerd.lyricsnative.sing.VocalSeparator;
import com.lenerd.spotifyplus.sdk.SpotifyPlusEvents;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class LyricsGenerator implements AutoCloseable {
    private static final String TAG = "LyricsGeneration";
    private static final boolean SERVER_GENERATION = false;

    private static final boolean DEBUG_ALIGNMENT = false;
    private static final int MAX_LYRICS_BYTES = 512 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API = "https://spotifyplus-api.devon-shoutz.workers.dev";
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024, MAX_STEM_BYTES = 32L * 1024 * 1024;
    private static final long POLL_INTERVAL_MS = 10_000, POLL_LIMIT_MS = 12 * 60 * 1000;
    private static final MediaType M4A = MediaType.get("audio/mp4");

    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(7, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "LyricsGeneration"));
    private final Consumer<Object> request = this::request;

    private SpotifyPlusContext context;
    private SpotifyPlusEvents events;
    private SpotifySongAudioSource audioSource;
    private volatile String active;
    private volatile boolean closed;
    private volatile String sessionId;

    public synchronized void register(SpotifyPlusContext context) {
        this.context = context;
        events = context.getEvents();
        audioSource = new SpotifySongAudioSource(context);
        events.on("lyrics.generate.request", request);
    }

    private synchronized void request(Object payload) {
        if (closed || !(payload instanceof JSONObject data)) return;

        String trackId = data.optString("trackId", "");

        if (!trackId.matches("[A-Za-z0-9]{22}")) {
            result(trackId, "failed", "Invalid Spotify track ID", data.optString("sessionId", ""));
            return;
        }

        if (active != null) {
            result(trackId, "failed", "Lyrics are already being generated for another song", data.optString("sessionId", ""));
            return;
        }

        active = trackId;
        sessionId = data.optString("sessionId", java.util.UUID.randomUUID().toString());
        Map<String, String> readings = readings(data.optJSONObject("readings"));
        double position = data.optDouble("positionMs", 0);
        double startPosition = Double.isFinite(position) ? Math.max(0, position) : 0;

        SpotifySongAudioSource.Ticket sourceTicket;
        try {
            sourceTicket = SERVER_GENERATION ? null : audioSource.begin(trackId, sessionId);
        } catch (IOException error) {
            result(trackId, "failed", error.getMessage());
            active = null;
            return;
        }

        try {
            worker.execute(() -> {
                try {
                    if (SERVER_GENERATION) generateOnServer(trackId);
                    else generateLocally(trackId, startPosition, readings, sourceTicket);
                } catch (Throwable error) {
                    if (closed) {
                        Log.i(TAG, "Generation cancelled for " + trackId);
                        return;
                    }

                    Log.e(TAG, "Generation failed for " + trackId, error);
                    result(trackId, "failed", error instanceof GenerationException ? error.getMessage() : "Could not generate lyrics: " + error.getClass().getSimpleName());
                } finally {
                    audioSource.finish(sourceTicket);
                    active = null;
                }
            });
        } catch (RejectedExecutionException error) {
            audioSource.finish(sourceTicket);
            active = null;
        }
    }

    private void generateLocally(String trackId, double position, Map<String, String> readings, SpotifySongAudioSource.Ticket sourceTicket) throws Exception {
        progress(trackId, "lyrics", "Finding lyrics");
        JSONArray lines = sourceLines(trackId);

        JSONObject sourceLyrics = new JSONObject().put("Type", "Line").put("Content", lines);
        events.emit("lyrics.generate.begin", new JSONObject().put("trackId", trackId).put("sessionId", sessionId).put("source", sourceLyrics));

        File directory = new File(System.getProperty("java.io.tmpdir"), "lyrics-generation-" + trackId);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create a temporary folder");

        File source = new File(directory, "source"), pcm = new File(directory, "source.pcm");
        ExecutorService preparation = Executors.newSingleThreadExecutor(r -> new Thread(r, "LyricsAudioPreparation"));
        Future<File> modelDownload = preparation.submit(() -> AlignmentModel.ensure(modelDirectory(), client, percent -> progress(trackId, "model", "Downloading alignment model " + percent + "%"), message -> Log.w(TAG, message)));

        try {
            progress(trackId, "downloading", "Reading song");
            try {
                audioSource.writeCurrentTrack(sourceTicket, source, message -> progress(trackId, "downloading", message));
            } catch (IOException error) {
                throw new GenerationException(error.getMessage());
            }

            progress(trackId, "decoding", "Decoding audio");
            AudioCodec.Decoded decoded = AudioCodec.decodeToPcm(source, pcm);
            source.delete();

            File model = ChunkPipeline.await(modelDownload);
            List<GenerationPlan.Line> planLines = new ArrayList<>();
            double duration = decoded.frames() * 1000.0 / decoded.rate();
            Log.i(TAG, "Decoded recording: rate=" + decoded.rate() + "; frames=" + decoded.frames() + "; durationMs=" + duration);

            for (int i = 0; i < lines.length(); i++) {
                JSONObject line = lines.optJSONObject(i);
                if (line == null || !"Vocal".equals(line.optString("Type"))) continue;
                double start = line.optDouble("StartTime", Double.NaN) * 1000, end = line.optDouble("EndTime", Double.NaN) * 1000;
                boolean empty = SyllableLyrics.tokenize(line.optString("Text")).isEmpty();
                if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end <= start || start >= duration || empty) {
                    Log.w(TAG, "Rejected lyric line: index=" + i + "; startMs=" + start + "; endMs=" + end + "; durationMs=" + duration + "; empty=" + empty);
                    throw new GenerationException("This song contains invalid lyric lines");
                }
                planLines.add(new GenerationPlan.Line(i, start, Math.min(end, duration)));
            }

            if (planLines.isEmpty()) throw new GenerationException("This song has no lyrics to time");
            SyllableLyrics.Line[] generated = new SyllableLyrics.Line[lines.length()];
            int[] completed = {0};

            progress(trackId, "generating", "Starting lyrics generation");
            try (RandomAccessFile file = new RandomAccessFile(pcm, "r"); VocalSeparator separator = new VocalSeparator(VocalSeparator.bundledModel()); AlignmentModel aligner = new AlignmentModel(model)) {
                if (file.length() > Integer.MAX_VALUE) throw new GenerationException("This song is too long to generate lyrics for");
                ShortBuffer samples = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                Hyphenator hyphenator = Hyphenator.bundled();
                OfflineSeparator.WindowCache separatedWindows = new OfflineSeparator.WindowCache();
                List<GenerationPlan.Chunk> chunks = GenerationPlan.create(planLines, currentPosition(trackId, position), duration);

                ChunkPipeline.run(chunks, chunk -> prepareChunk(samples, decoded, chunk, separator, separatedWindows), (chunk, prepared) -> {
                    LineAligner.Audio audio = new LineAligner.Audio() {
                        @Override
                        public int length() { return prepared.vocals.length; }

                        @Override
                        public float[] slice(int start, int end) { return java.util.Arrays.copyOfRange(prepared.vocals, start, end); }
                    };
                    LineAligner lineAligner = new LineAligner(audio, aligner::emissions, hyphenator, prepared.analysis, new AlignmentTuning(), message -> Log.i(TAG, message), DEBUG_ALIGNMENT).withReadings(readings);

                    for (GenerationPlan.Line planned : chunk.lines()) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        JSONObject line = lines.getJSONObject(planned.index());
                        List<SyllableLyrics.Token> tokens = SyllableLyrics.tokenize(line.getString("Text"));
                        List<String> words = new ArrayList<>();
                        for (SyllableLyrics.Token token : tokens) words.add(token.text());

                        long started = System.nanoTime();
                        List<LineAligner.Word> timed = lineAligner.alignLine(words, planned.start() - prepared.offset, planned.end() - prepared.offset);
                        Log.i(TAG, String.format(Locale.ROOT, "Aligned line %d in %.2fs", planned.index(), (System.nanoTime() - started) / 1e9));
                        for (int w = 0; w < timed.size(); w++) timed.set(w, timed.get(w).shifted(prepared.offset));
                        SyllableLyrics.Line aligned = new SyllableLyrics.Line(line.optBoolean("OppositeAligned"), tokens, timed);
                        generated[planned.index()] = aligned;
                        JSONObject vocal = SyllableLyrics.build(List.of(aligned)).getJSONArray("Content").getJSONObject(0);
                        events.emit("lyrics.generate.line", new JSONObject().put("trackId", trackId).put("sessionId", sessionId).put("index", planned.index()).put("line", vocal).put("completed", ++completed[0]).put("total", planLines.size()));
                    }
                }, preparation);
            }

            List<SyllableLyrics.Line> ordered = new ArrayList<>();
            for (SyllableLyrics.Line line : generated)
                if (line != null) ordered.add(line);
            JSONObject lyrics = SyllableLyrics.build(ordered);

            progress(trackId, "uploading", "Uploading lyrics");
            submit(trackId, lyrics);
            result(trackId, "complete", null);
        } finally {
            client.dispatcher().cancelAll();
            modelDownload.cancel(true);
            preparation.shutdownNow();

            boolean interrupted = Thread.interrupted();
            try {
                preparation.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
            }
            if (interrupted) Thread.currentThread().interrupt();
            for (File file : new File[] {source, pcm}) file.delete();
            directory.delete();
        }
    }

    private record PreparedChunk(float[] vocals, MusicAnalysis analysis, double offset) { }

    private PreparedChunk prepareChunk(ShortBuffer source, AudioCodec.Decoded decoded, GenerationPlan.Chunk chunk, VocalSeparator separator, OfflineSeparator.WindowCache cache) throws Exception {
        long started = System.nanoTime();
        int start = (int) Math.round(chunk.start() * decoded.rate() / 1000), end = (int) Math.min(decoded.frames(), Math.ceil(chunk.end() * decoded.rate() / 1000));
        float[] vocals = new float[Math.toIntExact(StreamingResampler.outputFrames(end - start, decoded.rate(), AlignmentModel.SAMPLE_RATE))];
        int[] written = {0};
        float[][] mono = new float[2][0];

        try (RhythmTrack vocalRhythm = new RhythmTrack(decoded.rate(), 80, 8000, false); RhythmTrack instrumentalRhythm = new RhythmTrack(decoded.rate(), 0, 11025, true)) {
            StreamingResampler resampler = new StreamingResampler(decoded.rate(), AlignmentModel.SAMPLE_RATE, (samples, count) -> {
                System.arraycopy(samples, 0, vocals, written[0], count);
                written[0] += count;
            });
            OfflineSeparator.separateRange(source, decoded.frames(), decoded.rate(), start, end, separator::separate, (voice, original, count) -> {
                if (closed) throw new InterruptedException();
                if (mono[0].length < count) mono[0] = new float[count];
                if (mono[1].length < count) mono[1] = new float[count];

                for (int i = 0; i < count; i++) {
                    mono[0][i] = (voice[0][i] + voice[1][i]) / 2;
                    mono[1][i] = (original[0][i] + original[1][i]) / 2 - mono[0][i];
                }
                vocalRhythm.write(mono[0], count);
                instrumentalRhythm.write(mono[1], count);
                resampler.write(mono[0], count);
            }, null, cache);
            resampler.finish();
            Log.i(TAG, String.format(Locale.ROOT, "Audio section %.1f–%.1fs prepared in %.2fs", chunk.start() / 1000, chunk.end() / 1000, (System.nanoTime() - started) / 1e9));
            return new PreparedChunk(java.util.Arrays.copyOf(vocals, written[0]), new MusicAnalysis(vocalRhythm.finish(), instrumentalRhythm.finish()), start * 1000.0 / decoded.rate());
        }
    }

    private double currentPosition(String trackId, double fallback) {
        try {
            var player = context.getPlayer();
            var track = player.getCurrentTrack();
            double position = player.getPlaybackPosition();
            if (track != null && trackId.equals(track.id) && Double.isFinite(position)) return Math.max(0, position);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not refresh playback position", error);
        }
        return fallback;
    }

    private static Map<String, String> readings(JSONObject json) {
        if (json == null) return Map.of();

        Map<String, String> readings = new HashMap<>();

        for (java.util.Iterator<String> keys = json.keys(); keys.hasNext();) {
            String word = keys.next(), reading = json.optString(word, "");
            if (!reading.isBlank()) readings.put(word, reading);
        }

        Log.i(TAG, "Aligning " + readings.size() + " words against their romanized readings");
        return readings;
    }

    private JSONArray sourceLines(String trackId) throws IOException, GenerationException {
        Request request = new Request.Builder().url(API + "/api/lyrics/" + trackId).header("Accept", "application/json").build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject body = json(response.body());

            if (response.code() == 404) throw new GenerationException("Could not find lyrics for this song");
            if (!response.isSuccessful()) throw new GenerationException(body.optString("error", "Could not load lyrics (HTTP " + response.code() + ")"));

            String type = body.optString("Type");
            if ("Syllable".equals(type)) throw new GenerationException("This song already has word synced lyrics");
            if (!"Line".equals(type)) throw new GenerationException("This song only has unsynced lyrics, so they can't be timed");

            JSONArray content = body.optJSONArray("Content");
            if (content == null || content.length() == 0) throw new GenerationException("This song has no lyrics to time");

            return content;
        }
    }

    private File modelDirectory() {
        android.app.Activity activity = context == null ? null : context.getActivity();
        File root = activity != null ? activity.getNoBackupFilesDir() : new File(System.getProperty("java.io.tmpdir"));

        return new File(root, "lyrics-models");
    }

    private void submit(String trackId, JSONObject lyrics) throws Exception {
        String body = new JSONObject().put("format", "json").put("lyrics", lyrics).toString();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_LYRICS_BYTES) throw new GenerationException("The generated lyrics are too large to upload");

        Request request = new Request.Builder().url(API + "/api/lyrics/" + trackId).post(RequestBody.create(body, JSON)).build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject json = json(response.body());
            if (!response.isSuccessful()) throw new GenerationException(json.optString("error", "Could not upload the lyrics (HTTP " + response.code() + ")"));
        }
    }

    private void generateOnServer(String trackId) throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"), "lyrics-generation-" + trackId);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create a temporary folder");

        File source = new File(directory, "source"), pcm = new File(directory, "source.pcm");
        File vocals = new File(directory, "vocals.m4a"), instrumental = new File(directory, "instrumental.m4a");

        try {
            progress(trackId, "downloading", "Downloading song");
            download(resolveAudioUrl(trackId), source);

            progress(trackId, "decoding", "Decoding audio");
            AudioCodec.Decoded decoded = AudioCodec.decodeToPcm(source, pcm);
            source.delete();

            progress(trackId, "splitting", "Separating vocals");
            split(trackId, pcm, decoded, vocals, instrumental);
            pcm.delete();

            if (vocals.length() > MAX_STEM_BYTES || instrumental.length() > MAX_STEM_BYTES) throw new GenerationException("This song is too long to generate lyrics for");

            progress(trackId, "generating", "Generating lyrics");
            upload(trackId, vocals, instrumental);
        } finally {
            for (File file : new File[] {source, pcm, vocals, instrumental}) file.delete();
            directory.delete();
        }
    }

    private String resolveAudioUrl(String trackId) throws IOException, GenerationException {
        Request request = new Request.Builder().url(API + "/api/download/" + trackId).header("Accept", "application/json").build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject body = json(response.body());

            if (!response.isSuccessful()) throw new GenerationException(body.optString("error", "Could not find audio for this song (HTTP " + response.code() + ")"));

            String url = body.optString("url", "");
            if (url.isEmpty()) throw new GenerationException("The download endpoint did not return an audio URL");

            return url;
        }
    }

    private void download(String url, File output) throws IOException, InterruptedException, GenerationException {
        try {
            ResumableDownload.fetch(client, url, output, MAX_DOWNLOAD_BYTES, true, "the song", () -> closed, (received, total) -> { }, message -> Log.w(TAG, message));
        } catch (ResumableDownload.Failure error) {
            throw new GenerationException(error.getMessage());
        }
    }

    private void split(String trackId, File pcm, AudioCodec.Decoded decoded, File vocals, File instrumental) throws Exception {
        float[][] backing = new float[2][];
        int[] lastPercent = {-1};

        try (RandomAccessFile file = new RandomAccessFile(pcm, "r"); VocalSeparator separator = new VocalSeparator(VocalSeparator.bundledModel()); AudioCodec.AacWriter vocalWriter = new AudioCodec.AacWriter(vocals, decoded.rate()); AudioCodec.AacWriter instrumentalWriter = new AudioCodec.AacWriter(instrumental, decoded.rate())) {
            var samples = file.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, file.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

            OfflineSeparator.separate(samples, decoded.frames(), decoded.rate(), separator::separate, (voice, original, count) -> {
                if (closed) throw new InterruptedException();

                for (int c = 0; c < 2; c++) {
                    if (backing[c] == null || backing[c].length < count) backing[c] = new float[voice[c].length];
                    for (int i = 0; i < count; i++) backing[c][i] = original[c][i] - voice[c][i];
                }

                vocalWriter.write(voice, count);
                instrumentalWriter.write(backing, count); }, fraction -> {
                int percent = (int) (fraction * 100);

                if (percent / 10 != lastPercent[0] / 10) {
                    lastPercent[0] = percent;
                    progress(trackId, "splitting", "Separating vocals " + percent + "%");
                } });

            vocalWriter.finish();
            instrumentalWriter.finish();
        }
    }

    private void upload(String trackId, File vocals, File instrumental) throws Exception {
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("vocals", "vocals.m4a", RequestBody.create(vocals, M4A)).addFormDataPart("instrumental", "instrumental.m4a", RequestBody.create(instrumental, M4A)).build();

        Request request = new Request.Builder().url(API + "/api/lyrics/" + trackId + "/generate").post(body).build();

        try (Response response = client.newCall(request).execute()) {
            JSONObject json = json(response.body());

            if (response.isSuccessful() && "complete".equals(json.optString("status"))) {
                result(trackId, "complete", null);
                return;
            }

            if (response.code() != 409 || !"processing".equals(json.optString("status"))) {
                throw new GenerationException(json.optString("error", "Lyrics generation failed (HTTP " + response.code() + ")"));
            }
        } catch (InterruptedIOException error) {
            if (closed) throw error;
            Log.w(TAG, "Generation request timed out, polling status instead", error);
        }

        poll(trackId);
    }

    private void poll(String trackId) throws Exception {
        long deadline = System.currentTimeMillis() + POLL_LIMIT_MS;
        Request request = new Request.Builder().url(API + "/api/lyrics/" + trackId + "/generate").build();

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS);

            try (Response response = client.newCall(request).execute()) {
                JSONObject json = json(response.body());
                String status = json.optString("status");

                if ("complete".equals(status)) {
                    result(trackId, "complete", null);
                    return;
                }

                if ("failed".equals(status) || !response.isSuccessful()) {
                    throw new GenerationException(json.optString("error", "Lyrics generation failed"));
                }
            } catch (IOException error) {
                if (closed) throw error;
                Log.w(TAG, "Status check failed, retrying", error);
            }
        }

        throw new GenerationException("Lyrics generation is taking too long, check back later");
    }

    private static JSONObject json(ResponseBody body) throws IOException {
        if (body == null) return new JSONObject();

        try {
            return new JSONObject(body.string());
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void progress(String trackId, String stage, String message) {
        if (closed) return;

        Log.i(TAG, trackId + " " + message);
        events.emit("lyrics.generate.progress", new JSONObject(Map.of("trackId", trackId, "sessionId", sessionId == null ? "" : sessionId, "stage", stage, "message", message)));
    }

    private void result(String trackId, String status, String error) {
        result(trackId, status, error, trackId.equals(active) && sessionId != null ? sessionId : "");
    }

    private void result(String trackId, String status, String error, String resultSession) {
        if (closed) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("trackId", trackId);
        payload.put("status", status);
        payload.put("sessionId", resultSession);
        if (error != null) payload.put("error", error);

        events.emit("lyrics.generate.result", new JSONObject(payload));
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (events != null) events.off("lyrics.generate.request", request);
        if (audioSource != null) audioSource.close();

        worker.shutdownNow();
        client.dispatcher().cancelAll();
    }

    private static final class GenerationException extends Exception {
        GenerationException(String message) { super(message); }
    }
}
