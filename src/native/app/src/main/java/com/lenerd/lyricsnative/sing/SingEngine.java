package com.lenerd.lyricsnative.sing;

import android.media.AudioTrack;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.lenerd.spotifyplus.sdk.*;
import com.lenerd.spotifyplus.sdk.hooks.*;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SingEngine implements SpotifyPlusPlugin {
    private static final String TAG = "SpotifySing";

    private SpotifyPlusHooks hooks;
    private SpotifyPlusContext context;
    private volatile VocalSeparator separator;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "SpotifySing-inference"));
    private final ScheduledExecutorService outputWorker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "SpotifySing-output"));
    private final AtomicBoolean workScheduled = new AtomicBoolean();

    private volatile boolean closed, enabled;
    private volatile float gain = 1;
    private volatile int lyricAdvanceMs = 80;
    private SharedPreferences preferences;
    private volatile RealtimeBuffer mixer;
    private volatile Object driver;
    private volatile long outputBase;

    private long boundAt;
    private int queueLimitFrames, inputLimitFrames;
    private AudioTrack boundTrack;
    private int originalStartThreshold;
    private boolean endDelivered;
    private volatile String status = "Loading vocal model";
    private volatile double inferenceMs;
    private volatile long windows;
    private Method write, end, queued, position, getTrack, progress;
    private long lastLog;
    private volatile long lastClockLog;

    @Override
    public void register(SpotifyPlusRegistry registry, SpotifyPlusContext context) {
        this.context = context;
        hooks = context.getHooks();

        context.getEvents().on("lyrics.sing.setTimingOffset", payload -> {
            if (payload instanceof Number) setLyricAdvanceMs(((Number) payload).intValue());
            emitState();
        });
        context.getEvents().on("lyrics.sing.requestState", payload -> emitState());

        try {
            installHooks();
        } catch (Throwable error) {
            fail("Audio hooks unavailable", error);
            return;
        }

        outputWorker.scheduleWithFixedDelay(this::pump, 0, 5, TimeUnit.MILLISECONDS);
        worker.execute(() -> {
            VocalSeparator loaded = null;

            try {
                loaded = VocalSeparator.forSpotify(context);
                long started = System.nanoTime();
                float[][] tone = new float[2][SpectralTransform.SAMPLES];

                for (int i = 0; i < tone[0].length; i++) tone[0][i] = tone[1][i] = (float) (0.1 * Math.sin(i * 2 * Math.PI * 440 / 44100));

                loaded.separate(tone);
                inferenceMs = (System.nanoTime() - started) / 1e6;

                if (closed) {
                    loaded.close();
                    return;
                }

                separator = loaded;
                status = "Ready · waiting for local Spotify audio";
                Log.i(TAG, "MODEL_READY model=uvr spinning=false warmup_ms=" + inferenceMs + " window_ms=" + (1000.0 * SpectralTransform.SAMPLES / 44100));
            } catch (Throwable error) {
                if (loaded != null) {
                    try {
                        loaded.close();
                    } catch (Exception ignored) { }
                }

                fail("Model initialization failed", error);
            }
        });
    }

    private void installHooks() throws Exception {
        Class<?> type = hooks.findClass("com.spotify.playbacknative.AudioDriver");
        Map<String, Method> methods = SingAudioContract.resolve(type, AudioTrack.class);

        write = methods.get("write");
        end = methods.get("trackHasEnded");
        queued = methods.get("getQueuedSize");
        position = methods.get("getPosition");
        getTrack = methods.get("getAudioTrack");
        Class<?> clock = Class.forName("com.lenerd.spotifyplus.module.Utils", false, SingEngine.class.getClassLoader());
        progress = clock.getDeclaredMethod("getCurrentPlaybackPosition");
        if (progress.getReturnType() != long.class || !java.lang.reflect.Modifier.isStatic(progress.getModifiers())) throw new NoSuchMethodException("Unsupported Spotify playback clock: " + progress);

        hooks.before(write, call -> {
            if (closed || !enabled || separator == null) return;

            byte[] input = (byte[]) call.getArgs()[0];
            int length = (int) call.getArgs()[1];
            if (length <= 0 || length > input.length) return;

            synchronized (this) {
                RealtimeBuffer current = bind(call.getThisObject());
                if (current == null || length % current.frameBytes() != 0) return;
                if (endDelivered) return;

                int head = (int) hooks.invokeOriginal(position, driver);
                long pending = Math.max(0, outputBase + current.captured() - head);
                int allowed = (int) Math.max(0, inputLimitFrames - pending) * current.frameBytes();
                int accepted = current.capture(input, length, allowed);

                call.returnAndSkip(accepted);
            }

            scheduleInference();
        });

        hooks.after(queued, call -> {
            RealtimeBuffer current = mixer;
            Object owner = driver;
            long base = outputBase;

            if (enabled && current != null && call.getThisObject() == owner && call.getThrowable() == null) {
                int head = (int) hooks.invokeOriginal(position, owner);
                if (current == mixer) call.setResult((int) Math.max(0, base + current.captured() - head));
            }
        });

        hooks.after(methods.get("getFreeSize"), call -> {
            RealtimeBuffer current = mixer;
            Object owner = driver;
            long base = outputBase;

            if (enabled && current != null && call.getThisObject() == owner && call.getThrowable() == null) {
                int head = (int) hooks.invokeOriginal(position, owner);
                long pending = Math.max(0, base + current.captured() - head);
                if (current == mixer) call.setResult((int) Math.max(0, inputLimitFrames - pending) * current.frameBytes());
            }
        });

        hooks.after(progress, call -> {
            RealtimeBuffer current = mixer;

            if (enabled && current != null && current.rendered() > 0 && call.getThrowable() == null && (long) call.getResult() >= 0) {
                long raw = (long) call.getResult();
                call.setResult(Math.max(0, raw + lyricAdvanceMs));

                if (System.nanoTime() - lastClockLog > 10_000_000_000L) {
                    lastClockLog = System.nanoTime();
                    Log.i(TAG, "CLOCK raw_ms=" + raw + " reported_ms=" + call.getResult() + " advance_ms=" + lyricAdvanceMs);
                }
            }
        });

        for (String name : new String[] {"flush", "destroy"}) {
            hooks.before(methods.get(name), call -> {
                if (call.getThisObject() == driver) reset();
            });
        }

        hooks.before(methods.get("startPlayback"), call -> {
            if (call.getThisObject() == driver && endDelivered) reset();
        });

        hooks.before(end, call -> {
            synchronized (this) {
                RealtimeBuffer current = mixer;
                if (!enabled || current == null || call.getThisObject() != driver || current.captured() == 0 || endDelivered) return;

                current.end();
                call.returnAndSkip(null);
            }

            scheduleInference();
        });

        try {
            Class<?> oboe = hooks.findClass("com.spotify.playbacknative.OboeRenderer");
            hooks.before(hooks.findMethod(oboe, "onAudioTrackCreated"), call -> status = "Oboe audio path active · Java PCM unavailable");
        } catch (ReflectiveOperationException ignored) { }

        Log.i(TAG, "HOOKS_READY single PCM queue; original AudioTrack clock; seek reset; exact EOF drain");
    }

    private RealtimeBuffer bind(Object owner) throws Throwable {
        if (driver != null && driver != owner) return null;
        if (mixer != null) return mixer;

        AudioTrack track = (AudioTrack) hooks.invokeOriginal(getTrack, owner);
        if (track == null) return null;

        if (track.getChannelCount() != 2 || (track.getSampleRate() != 44100 && track.getSampleRate() != 48000) || (track.getAudioFormat() != 2 && track.getAudioFormat() != 4)) {
            status = "Unsupported PCM format · original audio";
            return null;
        }

        outputBase = (int) hooks.invokeOriginal(position, owner) + (int) hooks.invokeOriginal(queued, owner);
        queueLimitFrames = track.getBufferSizeInFrames();
        inputLimitFrames = Math.max(queueLimitFrames, (int) Math.ceil(track.getSampleRate() * 1.2));

        if (Build.VERSION.SDK_INT < 31) {
            status = "Spotify Sing requires Android 12 or newer";
            return null;
        }

        if (queueLimitFrames < (int) Math.ceil((SpectralTransform.SAMPLES - SpectralTransform.MARGIN) * track.getSampleRate() / 44100.0)) {
            status = "Audio buffer too small · original audio";
            return null;
        }

        driver = owner;
        boundAt = System.nanoTime();
        endDelivered = false;
        boundTrack = track;
        originalStartThreshold = track.getStartThresholdInFrames();

        track.setStartThresholdInFrames(512);

        mixer = new RealtimeBuffer(track.getSampleRate(), track.getAudioFormat());
        mixer.setGain(gain);

        Log.i(TAG, "PCM_BOUND rate=" + track.getSampleRate() + " encoding=" + track.getAudioFormat() + " queue_frames=" + queueLimitFrames + " input_frames=" + inputLimitFrames + " base=" + outputBase);
        return mixer;
    }

    private void scheduleInference() {
        if (closed || !workScheduled.compareAndSet(false, true)) return;

        try {
            worker.execute(() -> {
                long observed = -1;
                boolean observedEnd = false;
                RealtimeBuffer observedMixer = null;

                try {
                    RealtimeBuffer current;

                    while (!closed && enabled && (current = mixer) != null) {
                        observedMixer = current;
                        observed = current.captured();
                        observedEnd = current.ended();

                        RealtimeBuffer.Window window = current.takeWindow();
                        if (window == null) break;

                        long started = System.nanoTime();
                        float[][] vocals = separator.separate(window.pcm());
                        inferenceMs = (System.nanoTime() - started) / 1e6;
                        windows++;
                        current.publish(window, vocals);

                        status = String.format(java.util.Locale.ROOT, "Live · %.0f ms/window · audio and lyrics share the playback clock%s", inferenceMs, current.fallbackFrames() == 0 ? "" : " · CPU overload: original audio fallback");

                        if (System.nanoTime() - lastLog > 5_000_000_000L) {
                            lastLog = System.nanoTime();
                            Log.i(TAG, "LIVE model=uvr windows=" + windows + " inference_ms=" + inferenceMs + " captured=" + current.captured() + " rendered=" + current.rendered() + " separated_frames=" + current.separatedFrames() + " fallback_frames=" + current.fallbackFrames() + " vocals=" + gain);
                        }
                    }
                } catch (Throwable error) {
                    fail("Separation failed · original audio", error);
                } finally {
                    workScheduled.set(false);
                    if (!closed && enabled && mixer != null && (mixer != observedMixer || mixer.captured() != observed || mixer.ended() != observedEnd)) scheduleInference();
                }
            });
        } catch (RejectedExecutionException ignored) {
            workScheduled.set(false);
        }
    }

    private synchronized void pump() {
        RealtimeBuffer current = mixer;
        Object owner = driver;
        if (closed || !enabled || current == null || endDelivered) return;
        if (!current.ended() && current.rendered() == current.captured()) return;

        try {
            int hardwareQueued = Math.max(0, (int) hooks.invokeOriginal(queued, owner));
            int free = Math.max(0, queueLimitFrames - hardwareQueued);
            boolean fallback = current.rendered() > 0 && hardwareQueued < current.rate() / 25;
            RealtimeBuffer.Output output = current.output(Math.min(free, 4096), fallback);

            if (output.bytes().length > 0) {
                int accepted = (int) hooks.invokeOriginal(write, owner, output.bytes(), output.bytes().length);

                if (accepted > 0) {
                    if (current.rendered() == 0) Log.i(TAG, "FIRST_OUTPUT startup_ms=" + ((System.nanoTime() - boundAt) / 1e6));
                    current.commit(output, accepted);
                }
            }

            if (current.ended() && current.rendered() == current.captured()) {
                hooks.invokeOriginal(end, owner);
                endDelivered = true;
                Log.i(TAG, "TAIL_DRAINED source_frames=" + current.captured() + " output_frames=" + current.rendered());
            }
        } catch (Throwable error) {
            fail("Audio output failed · original audio", error);
        }
    }

    private synchronized void reset() {
        if (mixer != null) mixer.reset();

        if (boundTrack != null && Build.VERSION.SDK_INT >= 31) {
            try {
                boundTrack.setStartThresholdInFrames(originalStartThreshold);
            } catch (IllegalStateException ignored) { }
        }

        boundTrack = null;
        mixer = null;
        driver = null;
        endDelivered = false;
    }

    private void fail(String message, Throwable error) {
        boolean hadPending = mixer != null && mixer.captured() > mixer.rendered();
        long resumeMs = hadPending ? audiblePositionMs() : 0;

        status = message + ": " + error.getClass().getSimpleName();
        enabled = false;
        reset();
        Log.e(TAG, message, error);

        if (hadPending && !closed) {
            try {
                outputWorker.execute(() -> {
                    try {
                        context.getApi().Player.seek(resumeMs);
                    } catch (Throwable recovery) {
                        Log.e(TAG, "Decoder recovery failed", recovery);
                    }
                });
            } catch (RejectedExecutionException ignored) { }
        }
    }

    public synchronized void initializePreferences(Context androidContext) {
        if (preferences != null) return;

        preferences = androidContext.getApplicationContext().getSharedPreferences("beautiful-lyrics-sing", Context.MODE_PRIVATE);
        gain = Math.max(0, Math.min(1, preferences.getFloat("vocals", 1)));

        int previous = androidContext.getApplicationContext().getSharedPreferences("spotify-sing", Context.MODE_PRIVATE).getInt("lyricAdvanceMs", 80);
        lyricAdvanceMs = Math.max(-250, Math.min(250, preferences.getInt("lyricAdvanceMs", previous)));
        enabled = gain < 1;

        if (mixer != null) mixer.setGain(gain);
    }

    public boolean enabled() { return enabled; }
    public boolean ready() { return separator != null && !closed && Build.VERSION.SDK_INT >= 31; }
    public float gain() { return gain; }
    public int lyricAdvanceMs() { return lyricAdvanceMs; }

    public void setLyricAdvanceMs(int value) {
        lyricAdvanceMs = Math.max(-250, Math.min(250, value));
        if (preferences != null) preferences.edit().putInt("lyricAdvanceMs", lyricAdvanceMs).apply();
        Log.i(TAG, "CONTROL lyric_advance_ms=" + lyricAdvanceMs);
    }

    public String status() { return !enabled && ready() ? "Off · original audio" : status; }

    private long audiblePositionMs() {
        try {
            return Math.max(0, (long) hooks.invokeOriginal(progress, null));
        } catch (Throwable error) {
            return Math.max(0, context.getApi().Player.getState().optLong("positionMs", 0) - lyricAdvanceMs);
        }
    }

    public void setEnabled(boolean value) {
        if (enabled == value || !ready()) return;

        long positionMs = audiblePositionMs();

        synchronized (this) {
            reset();
            enabled = value;
        }

        boolean paused = context.getApi().Player.getState().optBoolean("isPaused", false);

        try {
            context.getApi().Player.seek(positionMs);
            if (paused && !closed && !context.getApi().Player.getState().optBoolean("isPaused", false)) context.getApi().Player.pause();
        } catch (IllegalStateException error) {
            Log.w(TAG, "Playback transition rejected", error);
        }

        Log.i(TAG, "CONTROL enabled=" + enabled + " seek_ms=" + positionMs);
        emitState();
    }

    public void setGain(float value) {
        gain = Math.max(0, Math.min(1, value));
        if (mixer != null) mixer.setGain(gain);
    }

    public void commitGain() {
        if (preferences != null) preferences.edit().putFloat("vocals", gain).apply();
        Log.i(TAG, "CONTROL vocals=" + gain);
    }

    public void emitInteraction(boolean active) {
        if (!closed) context.getEvents().emit("lyrics.sing.interaction", active);
    }

    public void emitState() {
        if (!closed) context.getEvents().emit("lyrics.sing.state", SpotifyPlusApi.json("vocals", gain, "enabled", enabled, "ready", ready(), "timingOffsetMs", lyricAdvanceMs, "model", "uvr", "status", status()));
    }

    @Override
    public void onUnload() {
        closed = true;
        enabled = false;
        reset();
        outputWorker.shutdownNow();

        worker.execute(() -> {
            try {
                if (separator != null) separator.close();
            } catch (Exception error) {
                Log.e(TAG, "Session close failed", error);
            } finally {
                QnnRuntime.unload();
            }
        });

        worker.shutdown();
    }
}
