package com.lenerd.lyricsnative.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class CtcAligner {
    static final int BLANK = 0, STAR = 32, CLASSES = 33;

    record Result(String text, double start, double end, double score, double rawStart, double rawEnd) {
        Result shifted(double offset) {
            return new Result(text, start + offset, end + offset, score, rawStart + offset, rawEnd + offset);
        }
    }

    private record Segment(int label, int start, int end) { }

    private record Span(int start, int end, int rawStart, int rawEnd) { }

    private static final int[] STAR_TOKEN = {STAR};

    private CtcAligner() { }

    static List<Result> align(float[][] emissions, List<String> chunks, List<int[]> tokens, double strideMs, double mergeThresholdMs, float starLogProbability) {
        for (float[] frame : emissions) frame[STAR] = starLogProbability;

        List<int[]> starred = new ArrayList<>(tokens.size() * 2);
        List<String> texts = new ArrayList<>(chunks.size() * 2);

        for (int i = 0; i < tokens.size(); i++) {
            starred.add(STAR_TOKEN);
            starred.add(tokens.get(i));
            texts.add(null);
            texts.add(chunks.get(i));
        }

        starred.add(STAR_TOKEN);
        texts.add(null);

        int length = 0;
        for (int[] token : starred) length += token.length;

        int[] targets = new int[length];
        int position = 0;

        for (int[] token : starred) {
            System.arraycopy(token, 0, targets, position, token.length);
            position += token.length;
        }

        float[] scores = new float[emissions.length];
        int[] path = forcedAlign(emissions, targets, scores, true);
        List<Span> spans = spans(starred, mergeRepeats(path));

        List<Result> results = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i) == null) continue;

            Span span = spans.get(i);

            double score = 0;
            for (int t = span.start; t < span.end; t++) score += scores[t];

            results.add(new Result(texts.get(i), span.start * strideMs, span.end * strideMs, score / Math.max(1, span.end - span.start), span.rawStart * strideMs, span.rawEnd * strideMs));
        }

        for (int i = 0; i < results.size() - 1; i++) {
            Result current = results.get(i), next = results.get(i + 1);
            double gap = next.start - current.end;

            if (gap > 0 && gap < mergeThresholdMs) results.set(i + 1, new Result(next.text, current.end, next.end, next.score, next.rawStart, next.rawEnd));
        }

        return results;
    }

    static int[] forcedAlign(float[][] logProbs, int[] targets, float[] scores) {
        return forcedAlign(logProbs, targets, scores, false);
    }

    private static int[] forcedAlign(float[][] logProbs, int[] targets, float[] scores, boolean optionalTrailingStar) {
        int frames = logProbs.length, labels = targets.length, states = 2 * labels + 1;
        if (labels == 0) throw new IllegalArgumentException("Empty transcript");

        int repeats = 0;
        for (int i = 1; i < labels; i++)
            if (targets[i] == targets[i - 1]) repeats++;
        if (frames < labels + repeats - (optionalTrailingStar ? 1 : 0)) throw new IllegalArgumentException("Targets are too long for this audio");

        float[][] alphas = new float[2][states];
        byte[][] back = new byte[frames][states];
        Arrays.fill(alphas[0], Float.NEGATIVE_INFINITY);

        int start = optionalTrailingStar || frames - (labels + repeats) > 0 ? 0 : 1, end = states == 1 ? 1 : 2;

        for (int i = start; i < end; i++) alphas[0][i] = logProbs[0][label(targets, i)];

        for (int t = 1; t < frames; t++) {
            if (!optionalTrailingStar && frames - t <= labels + repeats) {
                if (start % 2 == 1 && start / 2 + 1 < labels && targets[start / 2] != targets[start / 2 + 1]) start++;
                start++;
            }

            if (t <= labels + repeats) {
                if (end % 2 == 0 && end < 2 * labels && targets[end / 2 - 1] != targets[end / 2]) end++;
                end++;
            }

            float[] current = alphas[t % 2], previous = alphas[(t - 1) % 2];
            Arrays.fill(current, Float.NEGATIVE_INFINITY);

            int loop = start;

            if (start == 0) {
                current[0] = previous[0] + logProbs[t][BLANK];
                back[t][0] = 0;
                loop++;
            }

            for (int i = loop; i < end; i++) {
                float x0 = previous[i], x1 = previous[i - 1], x2 = Float.NEGATIVE_INFINITY;
                if (i % 2 != 0 && i != 1 && targets[i / 2] != targets[i / 2 - 1]) x2 = previous[i - 2];

                float best;

                if (x2 >= x1 && x2 > x0) {
                    best = x2;
                    back[t][i] = 2;
                } else if (x1 > x0) {
                    best = x1;
                    back[t][i] = 1;
                } else {
                    best = x0;
                    back[t][i] = 0;
                }

                current[i] = best + logProbs[t][label(targets, i)];
            }
        }

        float[] last = alphas[(frames - 1) % 2];
        int state = states > 1 && last[states - 2] >= last[states - 1] ? states - 2 : states - 1;

        if (optionalTrailingStar) {
            for (int i = states - 4; i < states - 2; i++)
                if (last[i] >= last[state]) state = i;
        }
        if (Float.isInfinite(last[state])) throw new IllegalStateException("No valid CTC alignment");

        int[] path = new int[frames];

        for (int t = frames - 1; t >= 0; t--) {
            int id = label(targets, state);
            path[t] = id;
            scores[t] = logProbs[t][id];
            state -= back[t][state];
        }

        return path;
    }

    private static int label(int[] targets, int state) {
        return state % 2 == 0 ? BLANK : targets[state / 2];
    }

    private static List<Segment> mergeRepeats(int[] path) {
        List<Segment> segments = new ArrayList<>();

        for (int i = 0; i < path.length;) {
            int j = i;
            while (j < path.length && path[j] == path[i]) j++;

            segments.add(new Segment(path[i], i, j - 1));
            i = j;
        }

        return segments;
    }

    private static List<Span> spans(List<int[]> tokens, List<Segment> segments) {
        List<int[]> intervals = new ArrayList<>();
        int letter = 0, index = 0, start = 0;

        for (int s = 0; s < segments.size(); s++) {
            if (index == tokens.size()) break;

            Segment segment = segments.get(s);
            if (segment.label == BLANK) continue;

            int[] token = tokens.get(index);
            if (segment.label != token[letter]) throw new IllegalStateException("CTC path does not match the transcript");

            if (letter == 0) start = s;

            if (letter == token.length - 1) {
                letter = 0;
                index++;
                intervals.add(new int[] {start, s});

                while (index < tokens.size() && tokens.get(index).length == 0) {
                    intervals.add(new int[] {s, s});
                    index++;
                }
            } else {
                letter++;
            }
        }

        boolean trailingStarOmitted = intervals.size() == tokens.size() - 1 && tokens.get(tokens.size() - 1) == STAR_TOKEN;
        if (intervals.size() != tokens.size() && !trailingStarOmitted) throw new IllegalStateException("CTC path ended before the transcript");

        List<Span> spans = new ArrayList<>(intervals.size());

        for (int i = 0; i < intervals.size(); i++) {
            int first = intervals.get(i)[0], last = intervals.get(i)[1];
            int rawStart = segments.get(first).start, rawEnd = segments.get(last).end + 1;
            int spanStart = rawStart, spanEnd = rawEnd;

            if (first > 0) {
                Segment previous = segments.get(first - 1);
                if (previous.label == BLANK) spanStart = i == 0 ? previous.start : (previous.start + previous.end) / 2;
            }

            if (last + 1 < segments.size()) {
                Segment next = segments.get(last + 1);

                if (next.label == BLANK) spanEnd = i == intervals.size() - 1 ? next.end + 1 : Math.max(rawEnd, Math.floorDiv(next.start + next.end, 2));
            }

            spans.add(new Span(spanStart, spanEnd, rawStart, rawEnd));
        }

        return spans;
    }
}
