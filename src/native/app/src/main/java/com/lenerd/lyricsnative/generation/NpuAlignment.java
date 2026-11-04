package com.lenerd.lyricsnative.generation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.os.Build;
import android.util.Log;
import com.lenerd.lyricsnative.sing.QnnRuntime;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;

final class NpuAlignment implements AutoCloseable {
    static final String WEIGHTS = "hybrid-alignment-378348ee.fp16.onnx", MODEL = "masked-alignment-ca945afb.onnx";
    private static final String WEIGHTS_SHA = "378348ee38b739cc77e77e3fb8502f0f40ed53da0dbf7401b24c25c8fafe03de", MODEL_SHA = "ca945afbdb299d5844a5c69711b0ce7c531b40cd0e95c969c8b7df5006bd5899";
    private static final String URL = "https://huggingface.co/Xenova/wav2vec2-base-960h/resolve/a19f851b3d42865797e410752b4c570c871e4825/onnx/model_fp16.onnx";
    private static final long WEIGHTS_SIZE = 189_192_204;
    private static final int SAMPLES = 80000, FRAMES = 249, CONVOLUTION_FRAMES = 15999;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession session;
    private OrtSession.SessionOptions options;

    static boolean supported() { return Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.toUpperCase(java.util.Locale.ROOT).startsWith("SM8750"); }

    static void ensure(SpotifyPlusContext context, File directory, OkHttpClient client, AlignmentModel.Progress progress, Consumer<String> log) throws InterruptedException {
        if (context == null || !supported()) return;
        File partial = new File(directory, WEIGHTS + ".part");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create NPU model folder");
            File model = new File(directory, MODEL);
            if (!model.isFile() || model.length() != 312565) {
                byte[] header = context.getApi().Assets.readBytes("assets/native/alignment/masked.onnx").get(30, TimeUnit.SECONDS);
                if (!hex(MessageDigest.getInstance("SHA-256").digest(header)).equals(MODEL_SHA)) throw new IOException("NPU alignment graph checksum mismatch");
                File temporary = new File(directory, MODEL + ".part");
                try {
                    Files.write(temporary.toPath(), header);
                    Files.move(temporary.toPath(), model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    temporary.delete();
                }
            }
            File weights = new File(directory, WEIGHTS);
            if (weights.isFile() && weights.length() == WEIGHTS_SIZE) return;
            int[] previous = {-1};
            ResumableDownload.fetch(client, URL, partial, WEIGHTS_SIZE, false, "the NPU alignment model", () -> Thread.currentThread().isInterrupted(), (received, total) -> {
                int percent = (int) (received * 100 / WEIGHTS_SIZE);
                if (percent / 10 != previous[0] / 10) { previous[0] = percent; progress.update(percent); }
            }, log);
            if (!validWeights(partial)) throw new IOException("NPU alignment weights checksum mismatch");
            Files.move(partial.toPath(), weights.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception | LinkageError error) {
            Log.w("LyricsGeneration", "NPU alignment preparation unavailable; retaining CPU", error);
        } finally {
            partial.delete();
        }
    }

    static NpuAlignment open(SpotifyPlusContext context, File directory) {
        if (context == null || !supported() || !new File(directory, MODEL).isFile() || !new File(directory, WEIGHTS).isFile()) return null;
        try {
            return new NpuAlignment(directory, QnnRuntime.install(context));
        } catch (Exception | LinkageError error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w("LyricsGeneration", "NPU alignment initialization unavailable; retaining CPU", error);
            return null;
        }
    }

    NpuAlignment(File directory, File runtime) throws Exception {
        File cacheDirectory = new File(directory, "qnn-align-ca945afb-ort1232-qnn2371-" + Integer.toHexString(Build.FINGERPRINT.hashCode()));
        if (!cacheDirectory.isDirectory() && !cacheDirectory.mkdirs()) throw new IOException("Cannot create NPU alignment cache");
        File cache = new File(cacheDirectory, "model_ctx.onnx");
        long start = System.nanoTime();
        if (cache.isFile()) {
            try {
                create(cache, runtime, cache, false);
                Log.i("LyricsGeneration", "Alignment backend=Qualcomm NPU (cached QNN HTP); setup_ms=" + (System.nanoTime() - start) / 1e6);
                return;
            } catch (OrtException | RuntimeException | LinkageError error) {
                Log.w("LyricsGeneration", "Rebuilding unusable NPU alignment cache", error);
            }
        }
        File[] stale = cacheDirectory.listFiles();
        if (stale != null) for (File file : stale) if (file.isFile()) Files.deleteIfExists(file.toPath());
        create(new File(directory, MODEL), runtime, cache, true);
        Log.i("LyricsGeneration", "Alignment backend=Qualcomm NPU (QNN HTP); setup_ms=" + (System.nanoTime() - start) / 1e6);
    }

    private void create(File model, File runtime, File cache, boolean compile) throws OrtException {
        var configured = new OrtSession.SessionOptions();
        try {
            configured.setIntraOpNumThreads(4);
            configured.setInterOpNumThreads(1);
            configured.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            configured.addConfigEntry("session.intra_op.allow_spinning", "0");
            configured.addConfigEntry("session.disable_cpu_ep_fallback", "1");
            configured.addConfigEntry("ep.context_file_path", cache.getAbsolutePath());
            if (compile) {
                configured.addConfigEntry("ep.context_enable", "1");
                configured.addConfigEntry("ep.context_embed_mode", "0");
            }
            QnnRuntime.configure(configured, runtime, "htp", "burst");
            session = environment.createSession(model.getAbsolutePath(), configured);
            options = configured;
        } catch (OrtException | RuntimeException | LinkageError error) {
            configured.close();
            throw error;
        }
    }

    static boolean accepts(int samples) { return samples >= 32000 && samples <= SAMPLES; }

    static boolean validWeights(File file) throws Exception { return file.length() == WEIGHTS_SIZE && digest(file).equals(WEIGHTS_SHA); }

    float[][] logits(float[] normalized) throws OrtException {
        if (session == null) throw new IllegalStateException("NPU alignment session is closed");
        if (!accepts(normalized.length)) throw new IllegalArgumentException("NPU alignment window is out of range");
        int frames = (normalized.length - 400) / 320 + 1;
        float[] input = Arrays.copyOf(normalized, SAMPLES), convolution = new float[CONVOLUTION_FRAMES], mask = new float[FRAMES];
        Arrays.fill(convolution, 0, (normalized.length - 10) / 5 + 1, 1);
        Arrays.fill(mask, 0, frames, 1);
        try (var samples = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), new long[] {1, SAMPLES}); var convolutionMask = OnnxTensor.createTensor(environment, FloatBuffer.wrap(convolution), new long[] {1, 1, CONVOLUTION_FRAMES}); var frameMask = OnnxTensor.createTensor(environment, FloatBuffer.wrap(mask), new long[] {1, FRAMES, 1}); var run = new OrtSession.RunOptions()) {
            run.addRunConfigEntry("qnn.htp_perf_mode", "burst");
            run.addRunConfigEntry("qnn.htp_perf_mode_post_run", "power_saver");
            try (var output = session.run(Map.of("input_values", samples, "convolution_mask", convolutionMask, "frame_mask", frameMask), run)) {
                FloatBuffer values = ((OnnxTensor) output.get(0)).getFloatBuffer();
                float[][] logits = new float[frames][32];
                boolean allBlank = true;
                for (float[] frame : logits) {
                    values.get(frame);
                    int best = 0;
                    for (int c = 0; c < frame.length; c++) {
                        if (!Float.isFinite(frame[c])) throw new IllegalStateException("Nonfinite NPU alignment result");
                        if (frame[c] > frame[best]) best = c;
                    }
                    if (best != 0) allBlank = false;
                }
                if (allBlank) throw new IllegalStateException("NPU alignment returned only blank frames");
                return logits;
            }
        }
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file.toPath())) {
            byte[] block = new byte[65536];
            for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    @Override
    public void close() throws OrtException {
        try {
            if (session != null) session.close();
        } finally {
            session = null;
            if (options != null) options.close();
            options = null;
        }
    }
}
