package com.lenerd.lyricsnative.generation;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Map;
import android.os.Build;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import okhttp3.OkHttpClient;

final class AlignmentModel implements AutoCloseable {
    interface Progress {
        void update(int percent);
    }

    static final int SAMPLE_RATE = 16000;
    static final double FRAME_MS = 20;

    private static final String REVISION = "0d4c5dd9249432d9c415ee33d53cad7a28159690";
    private static final String URL = "https://huggingface.co/MixDirective/wav2vec2-base-960h-onnx/resolve/" + REVISION + "/model.int8.onnx";
    private static final String SHA256 = "02bcb2e690107934e4636ff883a2623f2f4a186530d42594296df87711789202";
    private static final long SIZE = 121_954_282L;

    private static final int WINDOW = 30 * SAMPLE_RATE, CONTEXT = 2 * SAMPLE_RATE;
    private static final int WINDOW_FRAMES = 1500, CONTEXT_FRAMES = 100;
    private static final int MIN_SAMPLES = 400;

    private static final String LETTERS = "ETAONIHSRDLUMWCFGYPBVK'XJQZ";

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final OrtSession.SessionOptions options;
    private NpuAlignment npu;

    AlignmentModel(File model) throws OrtException {
        this(model, null);
    }

    AlignmentModel(File model, com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext context) throws OrtException {
        options = new OrtSession.SessionOptions();
        try {
            int threads = Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.toUpperCase(java.util.Locale.ROOT).startsWith("SM8750") ? Math.min(6, Runtime.getRuntime().availableProcessors()) : 4;
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            options.addConfigEntry("session.inter_op.allow_spinning", "0");
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            session = environment.createSession(model.getPath(), options);
            Log.i("LyricsGeneration", "Alignment backend=CPU threads=" + threads);
            npu = NpuAlignment.open(context, model.getParentFile());
        } catch (OrtException | RuntimeException | LinkageError error) {
            options.close();
            throw error;
        }
    }

    static int token(char c) {
        int index = LETTERS.indexOf(Character.toUpperCase(c));
        return index < 0 ? -1 : index + 5;
    }

    static File ensure(File directory, OkHttpClient client, Progress progress, java.util.function.Consumer<String> log) throws Exception {
        File model = new File(directory, "wav2vec2-base-960h-" + REVISION.substring(0, 8) + ".int8.onnx");
        if (model.isFile() && model.length() == SIZE) return model;

        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create the model folder");

        File partial = new File(directory, model.getName() + ".part");
        int[] lastPercent = {-1};

        try {
            ResumableDownload.fetch(client, URL, partial, SIZE, false, "the alignment model", () -> Thread.currentThread().isInterrupted(), (received, total) -> {
                int percent = (int) (received * 100 / SIZE);

                if (percent / 10 != lastPercent[0] / 10) {
                    lastPercent[0] = percent;
                    progress.update(percent);
                }
            }, log);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = new FileInputStream(partial)) {
                byte[] block = new byte[1 << 16];
                for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
            }

            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));

            if (!SHA256.equals(hex.toString())) throw new IOException("The alignment model download was corrupted");
            if (!partial.renameTo(model)) throw new IOException("Could not save the alignment model");

            return model;
        } finally {
            partial.delete();
        }
    }

    float[][] emissions(float[] audio) throws OrtException {
        if (audio.length < MIN_SAMPLES) return new float[0][];
        if (audio.length <= WINDOW) return logSoftmax(logits(audio));

        int windows = (audio.length + WINDOW - 1) / WINDOW;
        int frames = (int) Math.ceil(audio.length / (SAMPLE_RATE * FRAME_MS / 1000));
        float[][] result = new float[frames][];
        int filled = 0;

        for (int w = 0; w < windows && filled < frames; w++) {
            float[] input = new float[WINDOW + 2 * CONTEXT];
            int start = w * WINDOW - CONTEXT;

            for (int i = 0; i < input.length; i++) {
                int at = start + i;
                if (at >= 0 && at < audio.length) input[i] = audio[at];
            }

            float[][] window = logSoftmax(logits(input));

            for (int f = CONTEXT_FRAMES; f < Math.min(window.length, CONTEXT_FRAMES + WINDOW_FRAMES) && filled < frames; f++) result[filled++] = window[f];
        }

        return filled == frames ? result : java.util.Arrays.copyOf(result, filled);
    }

    private float[][] logits(float[] audio) throws OrtException {
        long started = System.nanoTime();
        String reason = npu == null ? "npu_unavailable" : !NpuAlignment.accepts(audio.length) ? "window_length" : "quiet_audio";
        double mean = 0, variance = 0;
        for (float sample : audio) mean += sample;
        mean /= audio.length;

        for (float sample : audio) variance += (sample - mean) * (sample - mean);
        variance /= audio.length;

        float scale = (float) (1 / Math.sqrt(variance + 1e-7));
        float[] normalized = new float[audio.length];
        for (int i = 0; i < audio.length; i++) normalized[i] = (float) ((audio[i] - mean) * scale);

        if (npu != null && NpuAlignment.accepts(audio.length) && variance > 1e-10) {
            try {
                float[][] result = npu.logits(normalized);
                Log.i("LyricsGeneration", String.format(java.util.Locale.ROOT, "PERF emissions backend=NPU audio_ms=%.2f elapsed_ms=%.2f", audio.length * 1000.0 / SAMPLE_RATE, (System.nanoTime() - started) / 1e6));
                return result;
            } catch (OrtException | IllegalStateException | LinkageError error) {
                reason = "npu_failure";
                Log.w("LyricsGeneration", "NPU alignment failed; retrying this and subsequent windows on CPU", error);
                try {
                    npu.close();
                } catch (OrtException | RuntimeException | LinkageError cleanup) {
                    error.addSuppressed(cleanup);
                }
                npu = null;
            }
        }

        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(normalized), new long[] {1, audio.length});
            OrtSession.Result result = session.run(Map.of("input_values", input))) {
            float[][][] logits = (float[][][]) result.get(0).getValue();
            Log.i("LyricsGeneration", String.format(java.util.Locale.ROOT, "PERF emissions backend=CPU reason=%s audio_ms=%.2f elapsed_ms=%.2f", reason, audio.length * 1000.0 / SAMPLE_RATE, (System.nanoTime() - started) / 1e6));
            return logits[0];
        }
    }

    private static float[][] logSoftmax(float[][] logits) {
        float[][] result = new float[logits.length][CtcAligner.CLASSES];

        for (int t = 0; t < logits.length; t++) {
            float[] row = logits[t];
            float max = Float.NEGATIVE_INFINITY;
            for (float value : row) max = Math.max(max, value);

            double sum = 0;
            for (float value : row) sum += Math.exp(value - max);

            float log = (float) (max + Math.log(sum));
            for (int c = 0; c < row.length && c < CtcAligner.STAR; c++) result[t][c] = row[c] - log;
            result[t][CtcAligner.STAR] = 0;
        }

        return result;
    }

    @Override
    public void close() throws OrtException {
        try {
            try {
                if (npu != null) npu.close();
            } finally {
                session.close();
            }
        } finally {
            options.close();
        }
    }
}
