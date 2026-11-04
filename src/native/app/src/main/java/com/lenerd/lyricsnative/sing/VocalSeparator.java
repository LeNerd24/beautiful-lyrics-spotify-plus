package com.lenerd.lyricsnative.sing;

import ai.onnxruntime.*;
import android.util.Log;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.nio.FloatBuffer;
import java.io.File;
import java.util.Map;

public final class VocalSeparator implements AutoCloseable {
    private static final Object CACHE_LOCK = new Object();
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private OrtSession session;
    private OrtSession.SessionOptions sessionOptions;
    private byte[] fallbackModel;
    private boolean qnn;
    private final File qnnDirectory;
    private final String performance;
    private final SpectralTransform transform = new SpectralTransform();
    private NativeSpectralTransform nativeTransform;
    private final boolean fastTransform;
    private final String postRunPerformance;
    private final float compensation;
    private FloatBuffer featureBuffer, predictionBuffer;
    private OnnxTensor featureTensor, predictionTensor;

    public static byte[] bundledModel() throws java.io.IOException {
        try (var stream = VocalSeparator.class.getClassLoader().getResourceAsStream("assets/models/vocals.onnx")) {
            if (stream == null) throw new IllegalStateException("Bundled vocal model missing");

            var model = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[16384];
            int count;

            while ((count = stream.read(block)) != -1) model.write(block, 0, count);
            return model.toByteArray();
        }
    }

    public VocalSeparator(byte[] model) throws OrtException {
        this(model, 1.03f);
    }

    public VocalSeparator(byte[] model, float compensation) throws OrtException {
        this(model, compensation, null);
    }

    public static VocalSeparator forSpotify(SpotifyPlusContext context) throws OrtException {
        return forContext(context, "power_saver");
    }

    public static VocalSeparator forGeneration(SpotifyPlusContext context) throws OrtException {
        return forContext(context, "burst");
    }

    private static VocalSeparator forContext(SpotifyPlusContext context, String performance) throws OrtException {
        byte[] model;
        try {
            model = bundledModel();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read vocal model", error);
        }
        File directory = null;
        try {
            QnnRuntime.requireDspAccess();
            directory = QnnRuntime.install(context);
        } catch (Exception | LinkageError error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w("SpotifySing", "QNN runtime unavailable; using CPU", error);
        }
        return new VocalSeparator(model, 1.03f, directory, performance);
    }

    VocalSeparator(byte[] model, float compensation, File directory) throws OrtException {
        this(model, compensation, directory, "power_saver");
    }

    VocalSeparator(byte[] model, float compensation, File directory, String performance) throws OrtException {
        this(model, compensation, directory, performance, performance.equals("burst"), "power_saver");
    }

    VocalSeparator(byte[] model, float compensation, File directory, String performance, boolean fastTransform, String postRunPerformance) throws OrtException {
        this.compensation = compensation;
        this.fastTransform = fastTransform;
        this.postRunPerformance = postRunPerformance;
        qnnDirectory = directory;
        this.performance = performance;

        if (directory != null && OrtEnvironment.getAvailableProviders().contains(OrtProvider.QNN)) {
            try {
                openAccelerated(model);
                fallbackModel = model;
                initializeTransform();
                return;
            } catch (OrtException | IllegalStateException | LinkageError error) {
                Log.w("SpotifySing", "QNN HTP initialization failed; using CPU", error);
            }
        }

        openSession(model, false);
        initializeTransform();
    }

    private void initializeTransform() {
        if (!fastTransform) return;
        try {
            nativeTransform = new NativeSpectralTransform();
            Log.i("SpotifySing", "Generation spectral backend=native NEON");
        } catch (RuntimeException | LinkageError error) {
            Log.w("SpotifySing", "Native spectral transform unavailable; retaining Java", error);
        }
    }

    private void openSession(byte[] model, boolean accelerate) throws OrtException {
        openSession(model, accelerate, null, false);
    }

    private void openAccelerated(byte[] model) throws OrtException {
        if (!fastTransform) {
            openSession(model, true);
            return;
        }
        synchronized (CACHE_LOCK) {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(model);
                StringBuilder identity = new StringBuilder(64);
                for (byte value : hash) identity.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                File directory = new File(qnnDirectory.getParentFile(), "vocal-" + identity + "-ort1232-qnn2371-" + Integer.toHexString(android.os.Build.FINGERPRINT.hashCode()));
                if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create vocal model cache");
                File cache = new File(directory, "model_ctx.onnx");
                long started = System.nanoTime();
                if (cache.isFile()) {
                    try {
                        openSession(model, true, cache, true);
                        Log.i("LyricsGeneration", "Vocal model cached setup_ms=" + (System.nanoTime() - started) / 1e6);
                        return;
                    } catch (OrtException | RuntimeException error) {
                        Log.w("LyricsGeneration", "Rebuilding unusable vocal model cache", error);
                    }
                }
                File[] stale = directory.listFiles();
                if (stale != null) for (File file : stale) if (file.isFile()) java.nio.file.Files.deleteIfExists(file.toPath());
                openSession(model, true, cache, false);
                Log.i("LyricsGeneration", "Vocal model compiled setup_ms=" + (System.nanoTime() - started) / 1e6);
                return;
            } catch (Exception error) {
                Log.w("LyricsGeneration", "Vocal model cache unavailable; opening original model", error);
            }
            openSession(model, true);
        }
    }

    private void openSession(byte[] model, boolean accelerate, File cache, boolean cached) throws OrtException {
        var options = new OrtSession.SessionOptions();
        OrtSession created = null;
        try {
            options.setIntraOpNumThreads(4);
            options.setInterOpNumThreads(1);

            options.addConfigEntry("session.intra_op.allow_spinning", "0");
            options.addConfigEntry("session.inter_op.allow_spinning", "0");
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            if (accelerate) configureQnn(options, qnnDirectory, performance);
            if (cache != null) {
                options.addConfigEntry("ep.context_file_path", cache.getAbsolutePath());
                if (!cached) {
                    options.addConfigEntry("ep.context_enable", "1");
                    options.addConfigEntry("ep.context_embed_mode", "0");
                }
            }
            created = cached ? environment.createSession(cache.getAbsolutePath(), options) : environment.createSession(model, options);
            var shape = ((TensorInfo) created.getInputInfo().get("input").getInfo()).getShape();
            if (shape.length != 4 || shape[1] != 4 || shape[2] != SpectralTransform.BINS) throw new IllegalArgumentException("Wrong MDX model shape");

            session = created;
            sessionOptions = options;
            qnn = accelerate;
            Log.i("SpotifySing", "MODEL_BACKEND=" + backend());
        } catch (OrtException | RuntimeException | LinkageError error) {
            if (created != null) {
                try {
                    created.close();
                } catch (OrtException cleanup) {
                    error.addSuppressed(cleanup);
                }
            }
            options.close();
            throw error;
        }
    }

    static void configureQnn(OrtSession.SessionOptions options, File directory) throws OrtException {
        configureQnn(options, directory, "power_saver");
    }

    private static void configureQnn(OrtSession.SessionOptions options, File directory, String performance) throws OrtException {
        options.addConfigEntry("session.disable_cpu_ep_fallback", "1");
        options.setSymbolicDimensionValue("batch_size", 1);
        options.setSymbolicDimensionValue("frames", SpectralTransform.TIMES);
        QnnRuntime.configure(options, directory, "htp", performance);
    }

    public String backend() { return qnn ? "Qualcomm NPU (QNN HTP, full model)" : "CPU"; }

    public synchronized float[][] separate(float[][] pcm) throws OrtException {
        if (session == null) throw new IllegalStateException("Vocal separator is closed");
        float[] features = nativeTransform == null ? transform.forward(pcm) : nativeTransform.forward(pcm);
        float[] prediction;
        try {
            prediction = predict(features);
        } catch (OrtException | IllegalStateException error) {
            if (!qnn) throw error;
            Log.w("SpotifySing", "QNN HTP inference failed; retrying on CPU", error);
            byte[] model = fallbackModel;
            close();
            openSession(model, false);
            prediction = predict(features);
        }

        float[][] vocals = nativeTransform == null ? transform.inverse(prediction) : nativeTransform.inverse(prediction);
        for (float[] channel : vocals) {
            for (int i = 0; i < channel.length; i++) channel[i] *= compensation;
        }
        return vocals;
    }

    private float[] predict(float[] features) throws OrtException {
        return fastTransform ? predictBuffered(features) : predictUnbuffered(features);
    }

    private float[] predictUnbuffered(float[] features) throws OrtException {
        try (var input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), new long[] {1, 4, SpectralTransform.BINS, SpectralTransform.TIMES}); var runOptions = new OrtSession.RunOptions()) {
            if (qnn) {
                runOptions.addRunConfigEntry("qnn.htp_perf_mode", performance);
                runOptions.addRunConfigEntry("qnn.htp_perf_mode_post_run", postRunPerformance);
            }
            try (var result = session.run(Map.of("input", input), runOptions)) {
                FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
                float[] prediction = new float[features.length];

                buffer.get(prediction);

                for (float value : prediction) {
                    if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite model output");
                }

                return prediction;
            }
        }
    }

    private float[] predictBuffered(float[] features) throws OrtException {
        if (featureTensor == null) {
            featureBuffer = java.nio.ByteBuffer.allocateDirect(features.length * Float.BYTES).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            predictionBuffer = java.nio.ByteBuffer.allocateDirect(features.length * Float.BYTES).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            long[] shape = {1, 4, SpectralTransform.BINS, SpectralTransform.TIMES};
            OnnxTensor input = OnnxTensor.createTensor(environment, featureBuffer, shape);
            try {
                predictionTensor = OnnxTensor.createTensor(environment, predictionBuffer, shape);
                featureTensor = input;
            } catch (OrtException | RuntimeException | Error error) {
                input.close();
                throw error;
            }
        }
        featureBuffer.clear();
        featureBuffer.put(features).rewind();
        try (var runOptions = new OrtSession.RunOptions()) {
            if (qnn) {
                runOptions.addRunConfigEntry("qnn.htp_perf_mode", performance);
                runOptions.addRunConfigEntry("qnn.htp_perf_mode_post_run", postRunPerformance);
            }
            try (var result = session.run(Map.of("input", featureTensor), java.util.Set.of(), Map.of(session.getOutputNames().iterator().next(), predictionTensor), runOptions)) {
                float[] prediction = new float[features.length];
                predictionBuffer.rewind();
                predictionBuffer.get(prediction);
                for (float value : prediction) if (!Float.isFinite(value)) throw new IllegalStateException("Nonfinite model output");
                return prediction;
            }
        }
    }

    @Override
    public synchronized void close() throws OrtException {
        try {
            if (session != null) session.close();
        } finally {
            session = null;
            if (featureTensor != null) featureTensor.close();
            if (predictionTensor != null) predictionTensor.close();
            featureTensor = predictionTensor = null;
            featureBuffer = predictionBuffer = null;
            if (sessionOptions != null) sessionOptions.close();
            sessionOptions = null;
            fallbackModel = null;
            if (nativeTransform != null) nativeTransform.close();
            nativeTransform = null;
        }
    }
}
