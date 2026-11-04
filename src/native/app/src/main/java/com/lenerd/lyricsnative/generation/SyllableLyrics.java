package com.lenerd.lyricsnative.generation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class SyllableLyrics {
    record Token(String text, int group) { }

    record Line(boolean oppositeAligned, List<Token> tokens, List<LineAligner.Word> words) { }

    private SyllableLyrics() { }

    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0, phrases = 0, group = 0;
        boolean phraseStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '(') {
                if (depth++ == 0) {
                    flush(tokens, current, group);
                    phraseStarted = false;
                }

                continue;
            }

            if (c == ')' && depth > 0) {
                if (--depth == 0) {
                    flush(tokens, current, group);
                    group = 0;
                }

                continue;
            }

            if (isWhitespace(c)) {
                flush(tokens, current, group);
                continue;
            }

            if (depth > 0 && !phraseStarted) {
                phraseStarted = true;
                group = ++phrases;
            }

            current.append(c);
        }

        flush(tokens, current, group);
        return tokens;
    }

    private static void flush(List<Token> tokens, StringBuilder current, int group) {
        if (current.length() == 0) return;

        tokens.add(new Token(current.toString(), group));
        current.setLength(0);
    }

    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿';
    }

    static JSONObject build(List<Line> lines) throws JSONException {
        return build(lines, null);
    }

    static JSONObject build(List<Line> lines, JSONArray songWriters) throws JSONException {
        JSONArray content = new JSONArray();
        long songStart = Long.MAX_VALUE, songEnd = Long.MIN_VALUE, previousStart = 0;

        for (Line line : lines) {
            int groups = 0;
            for (Token token : line.tokens) groups = Math.max(groups, token.group);

            List<List<long[]>> timings = new ArrayList<>();
            List<List<String>> texts = new ArrayList<>();
            List<List<Boolean>> joined = new ArrayList<>();

            for (int g = 0; g <= groups; g++) {
                timings.add(new ArrayList<>());
                texts.add(new ArrayList<>());
                joined.add(new ArrayList<>());
            }

            for (int w = 0; w < line.tokens.size(); w++) {
                int group = line.tokens.get(w).group;
                List<LineAligner.Syllable> syllables = line.words.get(w).syllables;

                for (int s = 0; s < syllables.size(); s++) {
                    LineAligner.Syllable syllable = syllables.get(s);
                    timings.get(group).add(new long[] {Math.round(syllable.start()), Math.round(syllable.end())});
                    texts.get(group).add(syllable.text());
                    joined.get(group).add(s < syllables.size() - 1);
                }
            }

            long lineStart = Long.MAX_VALUE;

            for (List<long[]> vocal : timings) {
                if (vocal.isEmpty()) continue;

                sanitize(vocal, previousStart);
                lineStart = Math.min(lineStart, vocal.get(0)[0]);
            }

            if (lineStart == Long.MAX_VALUE) continue;
            previousStart = lineStart;

            JSONObject entry = new JSONObject();
            entry.put("Type", "Vocal");
            entry.put("OppositeAligned", line.oppositeAligned);

            if (timings.get(0).isEmpty()) {
                JSONObject lead = new JSONObject();
                lead.put("StartTime", seconds(lineStart));
                lead.put("EndTime", seconds(lineStart));
                lead.put("Syllables", new JSONArray());
                entry.put("Lead", lead);
            } else {
                entry.put("Lead", vocal(timings.get(0), texts.get(0), joined.get(0)));
            }

            JSONArray background = new JSONArray();

            for (int g = 1; g <= groups; g++) {
                if (!timings.get(g).isEmpty()) background.put(vocal(timings.get(g), texts.get(g), joined.get(g)));
            }

            if (background.length() > 0) entry.put("Background", background);

            for (List<long[]> vocal : timings) {
                if (vocal.isEmpty()) continue;

                songStart = Math.min(songStart, vocal.get(0)[0]);
                songEnd = Math.max(songEnd, vocal.get(vocal.size() - 1)[1]);
            }

            content.put(entry);
        }

        if (content.length() == 0) throw new JSONException("No lyrics could be aligned");

        JSONObject lyrics = new JSONObject();
        lyrics.put("Type", "Syllable");
        lyrics.put("StartTime", seconds(songStart));
        lyrics.put("EndTime", seconds(songEnd));
        lyrics.put("Content", content);
        if (songWriters != null) lyrics.put("SongWriters", new JSONArray(songWriters.toString()));

        return lyrics;
    }

    static void sanitize(List<long[]> syllables, long minimumStart) {
        long previousEnd = minimumStart;

        for (long[] syllable : syllables) {
            syllable[0] = Math.max(syllable[0], previousEnd);
            syllable[1] = Math.max(syllable[1], syllable[0] + 1);
            previousEnd = syllable[1];
        }
    }

    private static JSONObject vocal(List<long[]> timings, List<String> texts, List<Boolean> joined) throws JSONException {
        JSONArray syllables = new JSONArray();

        for (int i = 0; i < timings.size(); i++) {
            JSONObject syllable = new JSONObject();
            syllable.put("Text", texts.get(i));
            syllable.put("StartTime", seconds(timings.get(i)[0]));
            syllable.put("EndTime", seconds(timings.get(i)[1]));
            syllable.put("IsPartOfWord", joined.get(i));
            syllables.put(syllable);
        }

        JSONObject vocal = new JSONObject();
        vocal.put("StartTime", seconds(timings.get(0)[0]));
        vocal.put("EndTime", seconds(timings.get(timings.size() - 1)[1]));
        vocal.put("Syllables", syllables);

        return vocal;
    }

    private static double seconds(long milliseconds) {
        return milliseconds / 1000.0;
    }
}
