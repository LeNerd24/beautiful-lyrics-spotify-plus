package com.lenerd.lyricsnative.generation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Hyphenator {
    private static final int LEFT = 2, RIGHT = 2;

    private record Pattern(int offset, int[] values) { }

    private final Map<String, Pattern> patterns = new HashMap<>();
    private final Map<String, int[]> cache = new HashMap<>();
    private final int longest;

    static Hyphenator bundled() throws IOException {
        try (InputStream stream = Hyphenator.class.getClassLoader().getResourceAsStream("assets/hyphenation/hyph_en_US.dic")) {
            if (stream == null) throw new IOException("Bundled hyphenation dictionary missing");
            return new Hyphenator(stream);
        }
    }

    Hyphenator(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        reader.readLine();

        int longest = 0;

        for (String line; (line = reader.readLine()) != null;) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("%") || line.startsWith("#") || line.startsWith("LEFTHYPHENMIN") || line.startsWith("RIGHTHYPHENMIN") || line.startsWith("COMPOUND")) continue;

            StringBuilder key = new StringBuilder();
            List<Integer> values = new ArrayList<>();
            int digit = 0;

            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);

                if (Character.isDigit(c)) {
                    digit = c - '0';
                } else {
                    values.add(digit);
                    key.append(c);
                    digit = 0;
                }
            }

            values.add(digit);

            int start = 0, end = values.size();
            while (start < end && values.get(start) == 0) start++;
            while (end > start && values.get(end - 1) == 0) end--;
            if (start == end) continue;

            int[] trimmed = new int[end - start];
            for (int i = 0; i < trimmed.length; i++) trimmed[i] = values.get(start + i);

            patterns.put(key.toString(), new Pattern(start, trimmed));
            longest = Math.max(longest, key.length());
        }

        this.longest = longest;
    }

    synchronized int[] positions(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        int[] points = cache.get(lower);

        if (points == null) {
            String pointed = "." + lower + ".";
            int[] references = new int[pointed.length() + 1];

            for (int i = 0; i < pointed.length() - 1; i++) {
                for (int j = i + 1; j <= Math.min(i + longest, pointed.length()); j++) {
                    Pattern pattern = patterns.get(pointed.substring(i, j));
                    if (pattern == null) continue;

                    for (int k = 0; k < pattern.values.length; k++) {
                        int at = i + pattern.offset + k;
                        if (at < references.length) references[at] = Math.max(references[at], pattern.values[k]);
                    }
                }
            }

            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < references.length; i++)
                if (references[i] % 2 == 1) found.add(i - 1);

            points = found.stream().mapToInt(Integer::intValue).toArray();
            cache.put(lower, points);
        }

        int right = word.length() - RIGHT;
        return java.util.Arrays.stream(points).filter(point -> point >= LEFT && point <= right).toArray();
    }

    List<String> pieces(String word) {
        List<String> pieces = new ArrayList<>();
        int[] points = positions(word);
        int previous = 0, p = 0;

        for (int i = 1; i <= word.length(); i++) {
            boolean beforeHyphen = i < word.length() && word.charAt(i) == '-';
            boolean cut = ((p < points.length && points[p] == i) && !beforeHyphen) || (word.charAt(i - 1) == '-' && i < word.length());
            while (p < points.length && points[p] <= i) p++;

            if (cut || i == word.length()) {
                if (i > previous) pieces.add(word.substring(previous, i));
                previous = i;
            }
        }

        return pieces;
    }
}
