
package com.lenerd.lyricsnative.translation;

import android.util.Log;

import com.lenerd.spotifyplus.sdk.SpotifyPlusEvents;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class TranslationBridge implements AutoCloseable {
    private final Map<String, LyricsTranslationService> jobs = new HashMap<>();
    private final Consumer<Object> request = this::translate;
    private final Consumer<Object> cancel = this::cancel;

    private SpotifyPlusEvents events;
    private boolean closed;

    public synchronized void register(SpotifyPlusContext context) {
        events = context.getEvents();
        events.on("lyrics.translation.request", request);
        events.on("lyrics.translation.cancel", cancel);
        Log.i("LyricsTranslation", "Native translation bridge registered");
    }

    private synchronized void translate(Object payload) {
        if (closed || !(payload instanceof JSONObject data)) return;

        String id = data.optString("requestId", "");
        JSONArray input = data.optJSONArray("lines");

        if (id.isEmpty()) return;
        if (input == null) {
            reply(id, Map.of(), "Native translation received no lyric lines.");
            return;
        }
        if (jobs.containsKey(id)) return;

        ArrayList<String> lines = new ArrayList<>();
        for (int i = 0; i < input.length(); i++) if (input.opt(i) instanceof String line) lines.add(line);

        LyricsTranslationService service = new LyricsTranslationService(data.optString("target", "en"));
        jobs.put(id, service);

        try {
            events.emit("lyrics.translation.result", new JSONObject(Map.of("requestId", id, "started", true)));
            Log.i("LyricsTranslation", "Started native job lines=" + lines.size());
            service.translateLines(lines, translations -> complete(id, service, translations));
        } catch (RuntimeException error) {
            jobs.remove(id);
            service.cancel();
            reply(id, Map.of(), "Native translation could not start: " + error.getMessage());
        }
    }

    private synchronized void complete(String id, LyricsTranslationService service, Map<String, TranslationResult> translations) {
        if (closed || jobs.get(id) != service) return;

        jobs.remove(id);
        Log.i("LyricsTranslation", "Completed native job translated=" + translations.size() + " error=" + service.getFailure());
        reply(id, translations, translations.isEmpty() ? service.getFailure() : null);
    }

    private void reply(String id, Map<String, TranslationResult> translations, String error) {
        ArrayList<JSONObject> values = new ArrayList<>();

        for (TranslationResult value : translations.values()) {
            values.add(new JSONObject(Map.of("sourceText", value.sourceText, "text", value.translatedText, "detectedLanguage", value.detectedLanguage)));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("requestId", id);
        payload.put("translations", new JSONArray(values));
        if (error != null) payload.put("error", error);

        events.emit("lyrics.translation.result", new JSONObject(payload));
    }

    private synchronized void cancel(Object payload) {
        if (!(payload instanceof JSONObject data)) return;

        LyricsTranslationService service = jobs.remove(data.optString("requestId", ""));
        if (service != null) service.cancel();
    }

    @Override
    public synchronized void close() {
        closed = true;

        if (events != null) {
            events.off("lyrics.translation.request", request);
            events.off("lyrics.translation.cancel", cancel);
        }

        for (LyricsTranslationService service : jobs.values()) service.cancel();
        jobs.clear();
    }
}
