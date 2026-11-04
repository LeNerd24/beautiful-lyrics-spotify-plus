package com.lenerd.lyricsnative.generation;

import android.util.Log;
import com.lenerd.lyricsnative.sing.QnnRuntime;
import com.lenerd.lyricsnative.sing.NativeSpectralTransform;
import com.lenerd.lyricsnative.sing.SpectralTransform;
import com.lenerd.lyricsnative.sing.VocalSeparator;
import com.lenerd.spotifyplus.sdk.SpotifyPlusPlugin;
import com.lenerd.spotifyplus.sdk.SpotifyPlusRegistry;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import okhttp3.OkHttpClient;

public final class NpuAlignmentSetupProbe implements SpotifyPlusPlugin {
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient();

    @Override
    public void register(SpotifyPlusRegistry registry, SpotifyPlusContext context) {
        worker.execute(() -> {
            try {
                float[][] tone = new float[2][SpectralTransform.SAMPLES];
                for (int c = 0; c < 2; c++) for (int i = 0; i < tone[c].length; i++) tone[c][i] = (float) (0.1 * Math.sin(i * (c == 0 ? 0.03 : 0.04)));
                try (var transform = new NativeSpectralTransform()) {
                    float[] expected = new SpectralTransform().forward(tone), actual = transform.forward(tone);
                    double error = 0, energy = 0;
                    for (int i = 0; i < expected.length; i++) { error += Math.pow(expected[i] - actual[i], 2); energy += expected[i] * expected[i]; }
                    if (Math.sqrt(error / energy) >= 2e-6) throw new IllegalStateException("Native FFT output mismatch");
                    Log.i("NpuAlignmentSetup", "NATIVE_FFT_READY relative_error=" + Math.sqrt(error / energy));
                }
                File directory = new File(context.getActivity().getNoBackupFilesDir(), "lyrics-models");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create model folder");
                File external = context.getActivity().getExternalCacheDir();
                File seed = external == null ? null : new File(external, "spotifyplus-alignment-seed.onnx");
                if (seed != null && seed.isFile()) {
                    if (!NpuAlignment.validWeights(seed)) throw new IllegalStateException("Invalid model seed checksum");
                    Files.copy(seed.toPath(), new File(directory, NpuAlignment.WEIGHTS).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.delete(seed.toPath());
                }
                NpuAlignment.ensure(context, directory, client, percent -> Log.i("NpuAlignmentSetup", "DOWNLOAD percent=" + percent), message -> Log.i("NpuAlignmentSetup", message));
                try (NpuAlignment model = NpuAlignment.open(context, directory)) {
                    if (model == null) throw new IllegalStateException("NPU alignment initialization failed");
                    float[] input = new float[80000];
                    for (int i = 0; i < input.length; i++) input[i] = (float) (0.2 * Math.sin(i * 0.05));
                    try {
                        float[][] output = model.logits(input);
                        if (output.length != 249) throw new IllegalStateException("Unexpected model output shape");
                        Log.i("NpuAlignmentSetup", "INFERENCE_READY frames=" + output.length);
                    } catch (IllegalStateException error) {
                        if (!error.getMessage().contains("only blank")) throw error;
                        Log.i("NpuAlignmentSetup", "SYNTHETIC_BLANK_GUARD verified");
                    }
                    Log.i("NpuAlignmentSetup", "READY cached NPU model inside Spotify; no lyric generation or uploads");
                }
                try (var separator = VocalSeparator.forGeneration(context)) {
                    separator.separate(tone);
                    long start = System.nanoTime();
                    float[][] output = separator.separate(tone);
                    for (float[] channel : output) for (float value : channel) if (!Float.isFinite(value)) throw new IllegalStateException("Invalid generation separation output");
                    Log.i("NpuAlignmentSetup", "FAST_PREPARATION_READY backend=" + separator.backend() + " window_ms=" + (System.nanoTime() - start) / 1e6);
                }
            } catch (Throwable error) {
                Log.e("NpuAlignmentSetup", "FAIL", error);
            }
        });
    }

    @Override
    public void onUnload() {
        worker.execute(QnnRuntime::unload);
        worker.shutdown();
        client.dispatcher().cancelAll();
    }
}
