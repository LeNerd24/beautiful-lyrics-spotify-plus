package com.lenerd.lyricsnative.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class GenerationPlan {
    record Line(int index, double start, double end) { }
    record Chunk(List<Line> lines, double start, double end) { }

    private static final int STARTUP_LINES = 8;
    private static final double WINDOW_MS = 6000, CONTEXT_MS = 1500;

    private GenerationPlan() { }

    static List<Chunk> create(List<Line> input, double position, double duration) {
        List<Line> lines = new ArrayList<>(input);
        lines.sort(Comparator.comparingDouble(Line::start).thenComparingInt(Line::index));
        int pivot = 0;
        while (pivot < lines.size() && lines.get(pivot).end <= position) pivot++;
        if (pivot == lines.size()) pivot = 0;

        List<Chunk> chunks = new ArrayList<>();
        append(chunks, lines.subList(pivot, lines.size()), duration);
        append(chunks, lines.subList(0, pivot), duration);
        return chunks;
    }

    private static void append(List<Chunk> chunks, List<Line> lines, double duration) {
        int ready = chunks.stream().mapToInt(chunk -> chunk.lines.size()).sum();
        for (int from = 0; from < lines.size();) {
            int to = from + 1;
            double start = lines.get(from).start, end = lines.get(from).end;
            if (ready >= STARTUP_LINES) {
                while (to < lines.size() && lines.get(to).end - start <= WINDOW_MS) end = Math.max(end, lines.get(to++).end);
            }
            chunks.add(new Chunk(List.copyOf(lines.subList(from, to)), Math.max(0, start - CONTEXT_MS), Math.min(duration, end + CONTEXT_MS)));
            ready += to - from;
            from = to;
        }
    }
}
