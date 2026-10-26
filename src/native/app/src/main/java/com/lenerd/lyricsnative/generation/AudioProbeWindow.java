package com.lenerd.lyricsnative.generation;

import java.util.LinkedHashMap;
import java.util.Map;

final class AudioProbeWindow {
    private final String trackId;
    private final long started, duration;
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private String reason;

    AudioProbeWindow(String trackId, long started, long duration) {
        this.trackId = trackId;
        this.started = started;
        this.duration = duration;
    }

    synchronized boolean accept(String currentTrackId, long now) {
        if (reason != null) return false;
        if (!trackId.equals(currentTrackId)) reason = "track-changed-or-unavailable";
        else if (now - started >= duration) reason = "timeout";
        return reason == null;
    }

    synchronized void add(String name, long value) {
        if (reason == null && value >= 0) counters.merge(name, value, Long::sum);
    }

    synchronized void stop(String value) {
        if (reason == null) reason = value;
    }
    synchronized String reason() { return reason; }
    synchronized Map<String, Long> counters() { return new LinkedHashMap<>(counters); }
    String trackId() { return trackId; }
    long elapsed(long now) { return Math.max(0, now - started); }
}
