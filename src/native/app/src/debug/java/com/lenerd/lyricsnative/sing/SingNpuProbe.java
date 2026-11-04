package com.lenerd.lyricsnative.sing;

import android.util.Log;
import com.lenerd.spotifyplus.sdk.SpotifyPlusPlugin;
import com.lenerd.spotifyplus.sdk.SpotifyPlusRegistry;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import com.lenerd.spotifyplus.sdk.hooks.HookHandle;
import android.media.AudioTrack;

public final class SingNpuProbe implements SpotifyPlusPlugin {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile boolean captured;
    private HookHandle capture;
    private RealtimeBuffer buffer;
    private final ArrayList<float[][]> windows = new ArrayList<>();

    @Override
    public void register(SpotifyPlusRegistry registry, SpotifyPlusContext context) {
        try {
            var hooks = context.getHooks();
            var type = hooks.findClass("com.spotify.playbacknative.AudioDriver");
            var methods = SingAudioContract.resolve(type, AudioTrack.class);
            capture = hooks.after(methods.get("write"), call -> {
                if (closed || captured || call.getThrowable() != null) return;
                int accepted = (int) call.getResult();
                byte[] input = (byte[]) call.getArgs()[0];
                if (accepted <= 0 || accepted > input.length) return;
                synchronized (windows) {
                    if (buffer == null) {
                        AudioTrack track = (AudioTrack) hooks.invokeOriginal(methods.get("getAudioTrack"), call.getThisObject());
                        if (track == null || track.getChannelCount() != 2) return;
                        buffer = new RealtimeBuffer(track.getSampleRate(), track.getAudioFormat());
                    }
                    if (accepted % buffer.frameBytes() != 0) return;
                    buffer.capture(input, accepted, accepted);
                    RealtimeBuffer.Window window;
                    while (windows.size() < 4 && (window = buffer.takeWindow()) != null) windows.add(window.pcm());
                    if (windows.size() == 4) {
                        captured = true;
                        worker.execute(() -> compareMusic(context));
                    }
                }
            });
            Log.i("SingNpuProbe", "Waiting for four local music windows; playback is unchanged");
        } catch (Exception error) {
            Log.e("SingNpuProbe", "Cannot observe local PCM", error);
        }
        worker.execute(() -> {
            try (var separator = VocalSeparator.forSpotify(context)) {
                if (closed) return;
                if (!separator.backend().contains("NPU")) throw new IllegalStateException("NPU probe fell back to CPU");
                float[][] tone = new float[2][SpectralTransform.SAMPLES];
                for (int i = 0; i < tone[0].length; i++) tone[0][i] = tone[1][i] = (float) (0.1 * Math.sin(i * 2 * Math.PI * 440 / 44100));
                separator.separate(tone);
                long start = System.nanoTime();
                for (int i = 0; i < 5 && !closed; i++) separator.separate(tone);
                if (!closed) Log.i("SingNpuProbe", "HOST_BACKEND=" + separator.backend() + " average_ms=" + (System.nanoTime() - start) / 5e6);
            } catch (Throwable error) {
                Log.e("SingNpuProbe", "Host inference probe failed", error);
            }
        });
    }

    private void compareMusic(SpotifyPlusContext context) {
        try (var accelerated = VocalSeparator.forSpotify(context); var cpu = new VocalSeparator(VocalSeparator.bundledModel())) {
            if (!accelerated.backend().contains("NPU")) throw new IllegalStateException("Real-music probe fell back to CPU");
            double error = 0, vocals = 0, original = 0, peak = 0;
            long npuNanos = 0, cpuNanos = 0;
            for (float[][] input : windows) {
                if (closed) return;
                long start = System.nanoTime();
                float[][] expected = cpu.separate(input);
                cpuNanos += System.nanoTime() - start;
                start = System.nanoTime();
                float[][] actual = accelerated.separate(input);
                npuNanos += System.nanoTime() - start;
                for (int channel = 0; channel < 2; channel++) {
                    for (int i = 0; i < input[channel].length; i++) {
                        double delta = actual[channel][i] - expected[channel][i];
                        error += delta * delta;
                        vocals += expected[channel][i] * expected[channel][i];
                        original += input[channel][i] * input[channel][i];
                        peak = Math.max(peak, Math.abs(delta));
                    }
                }
            }
            Log.i("SingNpuProbe", "REAL_MUSIC backend=" + accelerated.backend() + " windows=" + windows.size() + " cpu_ms=" + cpuNanos / (windows.size() * 1e6) + " npu_ms=" + npuNanos / (windows.size() * 1e6) + " vocal_snr_db=" + 10 * Math.log10(vocals / Math.max(error, 1e-20)) + " error_to_input=" + Math.sqrt(error / Math.max(original, 1e-20)) + " peak_error=" + peak);
        } catch (Throwable error) {
            Log.e("SingNpuProbe", "Real-music comparison failed", error);
        }
    }

    @Override
    public void onUnload() {
        closed = true;
        if (capture != null) capture.unhook();
        worker.execute(QnnRuntime::unload);
        worker.shutdown();
    }
}
