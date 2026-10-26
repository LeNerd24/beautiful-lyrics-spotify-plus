package com.lenerd.lyricsnative.generation;

import android.media.MediaFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.Log;

import com.lenerd.spotifyplus.sdk.hooks.HookCall;
import com.lenerd.spotifyplus.sdk.SpotifyPlusEvents;
import com.lenerd.spotifyplus.sdk.hooks.HookHandle;
import com.lenerd.spotifyplus.sdk.hooks.SpotifyPlusHooks;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class SpotifyAudioProbe implements AutoCloseable {
    private static final String TAG = "LyricsAudioProbe";
    private static final Set<String> DOWNLOAD_METHODS = Set.of("GetMediaManifest", "GetMediaHandleId", "RequestData", "CreateFileStreamer", "CreateProgressiveFileStreamer", "DestroyFileStreamer", "IsFileFullyCached");
    private final SpotifyPlusContext context;
    private final SpotifyPlusEvents events;
    private final List<HookHandle> handles = new ArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "LyricsAudioProbe"));
    private final Consumer<Object> songChanged = ignored -> finish("song-changed");
    private AudioProbeWindow window;
    private ScheduledFuture<?> tick;
    private boolean closed;
    private long lastReport;
    private Field playerState;
    private Method stateTrack, hasTrack, unwrapTrack, trackUri;
    private JSONObject rendererState;

    SpotifyAudioProbe(SpotifyPlusContext context) {
        this.context = context;
        events = context.getEvents();
        events.on("songChanged", songChanged);
        try {
            Class<?> utils = Class.forName("com.lenerd.spotifyplus.module.Utils", false, SpotifyAudioProbe.class.getClassLoader());
            playerState = utils.getField("playerState");
            SpotifyPlusHooks hooks = context.getHooks();
            stateTrack = hooks.findClass("com.spotify.player.model.PlayerState").getMethod("track");
            Class<?> optional = stateTrack.getReturnType();
            hasTrack = optional.getMethod("c");
            unwrapTrack = optional.getMethod("b");
            trackUri = hooks.findClass("com.spotify.player.model.ContextTrack").getMethod("uri");
            Log.i(TAG, "READY renderer probe v2; current-track gate available; diagnostics run only on Generate Lyrics; no audio capture");
        } catch (ReflectiveOperationException | RuntimeException error) {
            playerState = null;
            Log.w(TAG, "Current-track gate unavailable (" + error.getClass().getSimpleName() + ")");
        }
    }

    synchronized void start(String trackId) {
        if (closed) return;
        finish("superseded");
        if (!trackId.equals(currentTrackId())) {
            Log.i(TAG, "SKIPPED requested track is not the current track");
            return;
        }

        window = new AudioProbeWindow(trackId, SystemClock.elapsedRealtime(), 60_000);
        lastReport = SystemClock.elapsedRealtime();
        SpotifyPlusHooks hooks = context.getHooks();
        observe(hooks, "com.spotify.contentdelivery.mediaprocessingimpl.NativeMediaProcessorFactoryImpl", "createProcessor");
        for (String name : new String[] {"process", "processSample", "setContext", "destroy"}) observe(hooks, "com.spotify.contentdelivery.mediaprocessingimpl.NativeMediaProcessorImpl", name);
        for (String name : new String[] {"callSingle", "callStream", "callSync"}) observe(hooks, "com.spotify.esperanto.esperanto.ClientBase", name);
        observe(hooks, "com.spotify.playbacknative.AudioDriver", "write");
        observe(hooks, "com.spotify.playbacknative.OboeRenderer", "onAudioTrackCreated");
        for (String name : new String[] {"open", "decode", "seekToUs", "destroy", "onOutputBuffer"}) observe(hooks, "com.spotify.playbacknative.MediaCodecFileDecompressor", name);
        observe(hooks, "android.media.MediaCodec", "configure");
        rendererState = snapshotRenderer();
        tick = timer.scheduleWithFixedDelay(this::check, 1, 1, TimeUnit.SECONDS);
        report("started");
    }

    private void observe(SpotifyPlusHooks hooks, String owner, String name) {
        int installed = 0;
        try {
            for (Method method : hooks.findClass(owner).getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                handles.add(hooks.after(method, call -> observeCall(owner, name, call)));
                installed++;
            }
            window.add("hooks." + name, installed);
            if (installed == 0) window.add("missing." + name, 1);
        } catch (Throwable error) {
            window.add("unavailable." + name, 1);
            Log.w(TAG, "Hook unavailable: " + owner + "." + name + " (" + error.getClass().getSimpleName() + ")");
        }
    }

    private synchronized void observeCall(String owner, String name, HookCall call) {
        if (window == null || !window.accept(currentTrackId(), SystemClock.elapsedRealtime())) return;
        Object[] args = call.getArgs();
        String key = name;
        if (owner.endsWith("MediaCodecFileDecompressor")) key = "decompressor." + name;
        if (owner.endsWith("ClientBase")) {
            if (args.length < 2 || !"spotify.download.esperanto.proto.Download".equals(args[0])) return;
            key = "download." + (DOWNLOAD_METHODS.contains(args[1]) ? args[1] : "other");
        }
        window.add("calls." + key, 1);
        if (name.equals("configure")) window.add("configure.thread." + (Thread.currentThread().getName().equals("LyricsGeneration") ? "generator" : "other"), 1);
        if (call.getThrowable() != null) {
            window.add("errors." + key, 1);
            return;
        }
        if (name.equals("process") && args.length == 3 && args[2] instanceof Integer size) {
            window.add("process.offeredBytes", Math.max(0, size));
            if (Boolean.TRUE.equals(call.getResult())) window.add("process.successes", 1);
        } else if (name.equals("processSample") && call.getResult() instanceof Integer result) {
            if (result >= 0) window.add("processSample.nonnegativeResults", 1);
            else window.add("processSample.negativeResults", 1);
        } else if (name.equals("createProcessor") && call.getResult() != null) {
            String type = call.getResult().getClass().getSimpleName();
            window.add("factory." + (type.equals("Success") ? "success" : type.equals("Fail") ? "fail" : "other"), 1);
        } else if (name.equals("write") && call.getResult() instanceof Integer bytes) {
            window.add("audioDriver.acceptedBytes", Math.max(0, bytes));
        } else if (name.equals("configure") && args.length > 0 && args[0] instanceof MediaFormat format) {
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.matches("audio/[a-zA-Z0-9.+-]{1,48}")) window.add("codec." + mime, 1);
        }
    }

    private String currentTrackId() {
        try {
            if (playerState == null) return null;
            Object state = playerState.get(null);
            Object optional = state == null ? null : stateTrack.invoke(state);
            if (optional == null || !Boolean.TRUE.equals(hasTrack.invoke(optional))) return null;
            String uri = (String) trackUri.invoke(unwrapTrack.invoke(optional));
            return uri != null && uri.matches("spotify:track:[A-Za-z0-9]{22}") ? uri.substring("spotify:track:".length()) : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private JSONObject snapshotRenderer() {
        JSONObject result = new JSONObject();
        try {
            SpotifyPlusHooks hooks = context.getHooks();
            Class<?> driverType = hooks.findClass("com.spotify.playbacknative.AudioDriver");
            result.put("usingOboeFlag", hooks.findField(driverType, "sUsingOboe").getBoolean(null));
            Object session = hooks.findField(driverType, "sCurrentAudioSession").get(null);
            result.put("javaSessionPresent", session != null);
            Object sessions = hooks.findField(driverType, "sSessionToAudioDriverMap").get(null);
            Object driver = session instanceof Integer id && sessions instanceof SparseArray<?> map ? map.get(id) : null;
            result.put("javaDriverPresent", driver != null);
            if (driver != null) {
                for (String name : new String[] {"mOpen", "mPaused", "mPlaying"}) result.put(name, hooks.findField(driverType, name).getBoolean(driver));
                Object output = hooks.findMethod(driverType, "getAudioTrack").invoke(driver);
                if (output instanceof AudioTrack track) {
                    result.put("playState", track.getPlayState()).put("rate", track.getSampleRate()).put("channels", track.getChannelCount()).put("encoding", track.getAudioFormat()).put("headFrames", Integer.toUnsignedLong(track.getPlaybackHeadPosition()));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            try {
                result.put("rendererSnapshotError", error.getClass().getSimpleName());
            } catch (Exception ignored) { }
        } catch (org.json.JSONException ignored) { }
        try {
            Object state = playerState == null ? null : playerState.get(null);
            if (state != null) {
                Class<?> type = stateTrack.getDeclaringClass();
                for (String name : new String[] {"isPlaying", "isPaused", "isBuffering"}) result.put(name, type.getMethod(name).invoke(state));
                Object format = type.getMethod("format").invoke(state);
                if (format != null && Boolean.TRUE.equals(hasTrack.invoke(format))) {
                    Object value = unwrapTrack.invoke(format);
                    if (value instanceof String text && text.matches("[A-Za-z0-9_.+/-]{1,64}")) result.put("playerFormat", text);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            try {
                result.put("playerSnapshotError", error.getClass().getSimpleName());
            } catch (Exception ignored) { }
        } catch (org.json.JSONException ignored) { }
        return result;
    }

    private synchronized void check() {
        try {
            if (window == null) return;
            long now = SystemClock.elapsedRealtime();
            if (!window.accept(currentTrackId(), now)) finish(window.reason());
            else if (now - lastReport >= 5_000) {
                lastReport = now;
                rendererState = snapshotRenderer();
                report("observing");
            }
        } catch (Throwable error) {
            Log.w(TAG, "Probe stopped (" + error.getClass().getSimpleName() + ")");
            finish("diagnostic-error");
        }
    }

    synchronized void finish(String reason) {
        if (window == null) return;
        window.stop(reason);
        if (tick != null) tick.cancel(false);
        tick = null;
        for (HookHandle handle : handles) {
            try {
                handle.unhook();
            } catch (Throwable error) {
                Log.w(TAG, "Could not remove diagnostic hook", error);
            }
        }
        handles.clear();
        report("finished");
        window = null;
        rendererState = null;
    }

    private void report(String phase) {
        try {
            JSONObject report = new JSONObject().put("phase", phase).put("trackId", window.trackId()).put("elapsedMs", window.elapsed(SystemClock.elapsedRealtime())).put("reason", window.reason()).put("counters", new JSONObject(window.counters())).put("renderer", rendererState).put("attribution", "process-wide observations during current-track generation; prefetch and other media may overlap").put("audioCaptured", false);
            Log.i(TAG, report.toString());
        } catch (Exception error) {
            Log.w(TAG, "Could not format diagnostic report", error);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        finish("unloaded");
        events.off("songChanged", songChanged);
        timer.shutdownNow();
    }
}
