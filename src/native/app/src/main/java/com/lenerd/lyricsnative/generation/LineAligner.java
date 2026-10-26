package com.lenerd.lyricsnative.generation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LineAligner {
    interface Emissions {
        float[][] compute(float[] audio) throws Exception;
    }
    interface Audio {
        int length();
        float[] slice(int start, int end);
    }

    private static final double PADDING_MS = 350;

    private static final double CTC_MERGE_THRESHOLD_MS = 40;

    private static final double REPAIR_GAP_MS = 500;
    private static final int REPAIR_CONTEXT_BEFORE_WORDS = 1, REPAIR_CONTEXT_AFTER_WORDS = 4;
    private static final double REPAIR_PADDING_MS = 250, REPAIR_MIN_IMPROVEMENT_MS = 150, REPAIR_ANCHOR_TOLERANCE_MS = 450;

    private static final double OVERLAP_BOUNDARY_POSITION = 0.42, PAUSE_END_EXTENSION_MS = 70;

    private static final double MIN_SYLLABLE_MS = 55;

    private static final double MIN_VOCAL_SPLIT_SCORE = 0.24, MIN_VOCAL_PEAK_SPLIT_SCORE = 0.35, MIN_RHYTHM_SPLIT_SCORE = 0.16, MIN_SPLIT_SIDE_MS = 200;

    private static final double CTC_GAP_FULL_SCORE_MS = 180, CTC_GAP_BONUS_WEIGHT = 0.21, MIN_CTC_SPLIT_SCORE = 0.65;
    private static final double MIN_CTC_SUPPORT_RHYTHM_SCORE = 0.50, MIN_CTC_SUPPORT_INSTRUMENTAL_SCORE = 0.20;

    private static final double HYPHEN_SPLIT_BONUS = 1.0;

    private static final double VOCAL_ONSET_WINDOW_MS = 85, INSTRUMENTAL_ONSET_WINDOW_MS = 85, RHYTHM_TOLERANCE_MS = 80;
    private static final double VOCAL_SCORE_WEIGHT = 0.50, RHYTHM_SCORE_WEIGHT = 0.30, INSTRUMENTAL_SCORE_WEIGHT = 0.10, DURATION_SCORE_WEIGHT = 0.10;

    private static final double LATE_LINE_START_MS = 700, LATE_LINE_ONSET_WINDOW_MS = 500, LATE_LINE_MIN_ONSET_SCORE = 0.62;

    private static final Map<String, List<String>> ALIGNMENT_REPLACEMENTS = Map.ofEntries(Map.entry("i'm", List.of("i", "am")), Map.entry("i'll", List.of("i", "will")), Map.entry("i'd", List.of("i", "would")), Map.entry("i've", List.of("i", "have")), Map.entry("you're", List.of("you", "are")), Map.entry("you'll", List.of("you", "will")), Map.entry("you'd", List.of("you", "would")), Map.entry("you've", List.of("you", "have")), Map.entry("he's", List.of("he", "is")), Map.entry("he'll", List.of("he", "will")), Map.entry("he'd", List.of("he", "would")), Map.entry("she's", List.of("she", "is")), Map.entry("she'll", List.of("she", "will")), Map.entry("she'd", List.of("she", "would")), Map.entry("we're", List.of("we", "are")), Map.entry("we'll", List.of("we", "will")), Map.entry("we'd", List.of("we", "would")), Map.entry("we've", List.of("we", "have")), Map.entry("they're", List.of("they", "are")), Map.entry("they'll", List.of("they", "will")), Map.entry("they'd", List.of("they", "would")), Map.entry("they've", List.of("they", "have")), Map.entry("it's", List.of("it", "is")), Map.entry("that's", List.of("that", "is")), Map.entry("there's", List.of("there", "is")), Map.entry("what's", List.of("what", "is")), Map.entry("who's", List.of("who", "is")), Map.entry("can't", List.of("can", "not")), Map.entry("won't", List.of("will", "not")), Map.entry("don't", List.of("do", "not")), Map.entry("doesn't", List.of("does", "not")), Map.entry("didn't", List.of("did", "not")), Map.entry("isn't", List.of("is", "not")), Map.entry("aren't", List.of("are", "not")), Map.entry("wasn't", List.of("was", "not")), Map.entry("weren't", List.of("were", "not")), Map.entry("haven't", List.of("have", "not")), Map.entry("hasn't", List.of("has", "not")), Map.entry("hadn't", List.of("had", "not")), Map.entry("wouldn't", List.of("would", "not")), Map.entry("shouldn't", List.of("should", "not")), Map.entry("couldn't", List.of("could", "not")), Map.entry("mustn't", List.of("must", "not")), Map.entry("'cause", List.of("cause")), Map.entry("cause", List.of("cause")));

    private static final Map<String, List<String>> NUMBER_REPLACEMENTS = Map.ofEntries(Map.entry("0", List.of("zero")), Map.entry("1", List.of("one")), Map.entry("2", List.of("two")), Map.entry("3", List.of("three")), Map.entry("4", List.of("four")), Map.entry("5", List.of("five")), Map.entry("6", List.of("six")), Map.entry("7", List.of("seven")), Map.entry("8", List.of("eight")), Map.entry("9", List.of("nine")), Map.entry("10", List.of("ten")), Map.entry("11", List.of("eleven")), Map.entry("12", List.of("twelve")));

    private static final String WORD = "\\p{L}\\p{M}\\p{N}_";

    private static final Pattern CORE_EDGES = Pattern.compile("^[^" + WORD + "']+|[^" + WORD + "']+$");
    private static final Pattern NOT_TOKEN = Pattern.compile("[^" + WORD + "']+");
    private static final Pattern NOT_LETTER = Pattern.compile("[^a-z]");
    private static final Pattern STRONG_PAUSE = Pattern.compile("[.!?;:…][\"')\\]]*$");
    private static final Pattern SPELLING = Pattern.compile("^([^" + WORD + "]*)(.*?)([^" + WORD + "]*)$", Pattern.DOTALL);
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    static final class Word {
        final String text;
        double start, end, confidence;
        List<Char> chars = new ArrayList<>();
        List<Syllable> syllables;
        boolean fallback;

        Word(String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }

        Word copy() {
            Word word = new Word(text, start, end);
            word.confidence = confidence;
            word.chars = new ArrayList<>(chars);
            word.syllables = syllables == null ? null : new ArrayList<>(syllables);
            word.fallback = fallback;
            return word;
        }

        Word shifted(double offset) {
            Word word = copy();
            word.start += offset;
            word.end += offset;
            word.chars = new ArrayList<>();
            for (Char c : chars) word.chars.add(new Char(c.text(), c.start() + offset, c.end() + offset, c.score()));
            if (syllables != null) {
                word.syllables = new ArrayList<>();
                for (Syllable s : syllables) word.syllables.add(new Syllable(s.text(), s.start() + offset, s.end() + offset));
            }
            return word;
        }
    }

    record Char(String text, double start, double end, double score) { }
    record Syllable(String text, double start, double end) { }

    private record Weighted(double score, MusicAnalysis.Event event, double distance) { }
    private record Boundary(double time, double charGap, boolean characterTiming, boolean hyphen) { }
    private record Candidates(List<Syllable> syllables, List<Boundary> boundaries) { }

    private final Audio audio;
    private final Emissions model;
    private final Hyphenator hyphenator;
    private final MusicAnalysis analysis;
    private final Consumer<String> log;
    private final AlignmentTuning tuning;
    private final boolean verbose;
    private final double duration;
    private Map<String, String> readings = Map.of();

    LineAligner(Audio audio, Emissions model, Hyphenator hyphenator, MusicAnalysis analysis, Consumer<String> log) {
        this(audio, model, hyphenator, analysis, new AlignmentTuning(), log, true);
    }

    LineAligner(Audio audio, Emissions model, Hyphenator hyphenator, MusicAnalysis analysis, AlignmentTuning tuning, Consumer<String> log, boolean verbose) {
        this.tuning = tuning.copy();
        this.verbose = verbose;
        this.audio = audio;
        this.model = model;
        this.hyphenator = hyphenator;
        this.analysis = analysis;
        this.log = log;
        this.duration = audio.length() * 1000.0 / AlignmentModel.SAMPLE_RATE;
    }

    LineAligner withReadings(Map<String, String> readings) {
        this.readings = readings;
        return this;
    }

    List<Word> alignLine(List<String> displayWords, double lineStart, double lineEnd) throws InterruptedException {
        if (displayWords.isEmpty()) return List.of();

        double clipStart = Math.max(0, lineStart - PADDING_MS), clipEnd = Math.min(duration, lineEnd + PADDING_MS);

        try {
            List<Word> words = runAlignment(displayWords, clipStart, clipEnd, lineStart, lineEnd);

            if (words.size() != displayWords.size()) {
                log.accept("CTC incomplete (" + words.size() + "/" + displayWords.size() + " words); retrying with the exact line window");

                try {
                    words = runAlignment(displayWords, lineStart, lineEnd, lineStart, lineEnd);
                } catch (InterruptedException error) {
                    throw error;
                } catch (Exception error) {
                    log.accept("CTC retry failed: " + error);
                    words = List.of();
                }

                if (words.size() != displayWords.size()) {
                    log.accept("FALLBACK: estimated timings for all " + displayWords.size() + " words");
                    words = fallbackWordTimings(displayWords, lineStart, lineEnd);
                }
            }

            words = repairSuspiciousRegions(words, lineStart, lineEnd);
            words = repairLateLineStart(words, lineStart, lineEnd);
            words = refineWordStarts(words, lineStart);
            words = smoothWords(words, lineStart, lineEnd, tuning);

            for (Word word : words) word.syllables = musicalSyllables(word);

            return words;
        } catch (InterruptedException error) {
            throw error;
        } catch (Exception error) {
            log.accept("FAILED: " + error + "; preserving line with estimated word timings");

            List<Word> words = fallbackWordTimings(displayWords, lineStart, lineEnd);
            for (Word word : words) word.syllables = List.of(new Syllable(word.text, word.start, word.end));

            return words;
        }
    }

    private void debug(String message) {
        if (verbose) log.accept(message);
    }

    static String alignmentCore(String word) {
        return CORE_EDGES.matcher(word.replace('’', '\'')).replaceAll("");
    }

    static List<String> alignmentParts(String word) {
        if (word.trim().equals("&")) return List.of("and");

        String core = alignmentCore(word);
        if (core.isEmpty()) return List.of();

        String lower = core.toLowerCase(Locale.ROOT);

        if (ALIGNMENT_REPLACEMENTS.containsKey(lower)) return ALIGNMENT_REPLACEMENTS.get(lower);
        if (NUMBER_REPLACEMENTS.containsKey(lower)) return NUMBER_REPLACEMENTS.get(lower);

        String compact = NOT_LETTER.matcher(word.replace('’', '\'').toLowerCase(Locale.ROOT)).replaceAll("");

        if (compact.equals("am") && word.contains(".")) return List.of("a", "m");
        if (compact.equals("pm") && word.contains(".")) return List.of("p", "m");

        return List.of(lower);
    }

    static String vocabularyOnly(String part) {
        String folded = MARKS.matcher(Normalizer.normalize(part, Normalizer.Form.NFD)).replaceAll("");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < folded.length(); i++) {
            char c = Character.toLowerCase(folded.charAt(i));
            if (AlignmentModel.token(c) >= 0) result.append(c);
        }

        return result.toString();
    }

    static String normalizedToken(String text) {
        return NOT_TOKEN.matcher(text.replace('’', '\'').toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static boolean hasStrongPausePunctuation(String word) {
        return STRONG_PAUSE.matcher(word).find();
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private List<Word> runAlignment(List<String> displayWords, double clipStart, double clipEnd, double lineStart, double lineEnd) throws Exception {
        List<String> alignmentWords = new ArrayList<>();
        List<Integer> displayIndices = new ArrayList<>();

        for (int i = 0; i < displayWords.size(); i++) {
            String spoken = readings.getOrDefault(displayWords.get(i), displayWords.get(i));

            for (String piece : spoken.trim().split("\\s+")) {
                for (String part : alignmentParts(piece)) {
                    String usable = vocabularyOnly(part);
                    if (usable.isEmpty()) continue;

                    alignmentWords.add(usable);
                    displayIndices.add(i);
                }
            }
        }

        if (alignmentWords.isEmpty()) return List.of();

        clipStart = Math.max(0, Math.round(clipStart));
        clipEnd = Math.min(duration, Math.round(clipEnd));

        int startSample = (int) Math.max(0, Math.round(clipStart * AlignmentModel.SAMPLE_RATE / 1000));
        int endSample = (int) Math.min(audio.length(), Math.round(clipEnd * AlignmentModel.SAMPLE_RATE / 1000));
        if (endSample <= startSample) return List.of();

        float[][] emissions = model.compute(audio.slice(startSample, endSample));
        if (emissions.length == 0) return List.of();

        double offset = clipStart + tuning.frameOffsetMs;
        List<CtcAligner.Result> wordResults = alignWords(emissions, alignmentWords, offset, tuning.starLogProbability);
        List<CtcAligner.Result> charResults = alignCharacters(emissions, alignmentWords, offset, tuning.characterStarLogProbability);

        if (wordResults.size() != displayIndices.size()) {
            debug("  Word token mismatch: expected " + displayIndices.size() + ", got " + wordResults.size());
            return List.of();
        }

        List<List<CtcAligner.Result>> charGroups = new ArrayList<>();
        for (int i = 0; i < alignmentWords.size(); i++) charGroups.add(new ArrayList<>());

        int wordIndex = 0;

        for (CtcAligner.Result result : charResults) {
            if (result.text().trim().isEmpty()) {
                wordIndex++;
                continue;
            }

            if (normalizedToken(result.text()).isEmpty()) continue;
            if (wordIndex < charGroups.size()) charGroups.get(wordIndex).add(result);
        }

        Word[] mapped = new Word[displayWords.size()];
        List<List<Double>> confidences = new ArrayList<>();
        for (int i = 0; i < displayWords.size(); i++) confidences.add(new ArrayList<>());

        for (int a = 0; a < wordResults.size(); a++) {
            CtcAligner.Result result = wordResults.get(a);
            int display = displayIndices.get(a);

            double start = Math.max(wordStart(result), lineStart), end = Math.min(wordEnd(result), lineEnd);
            if (end <= start) continue;

            Word target = mapped[display];

            if (target == null) {
                target = mapped[display] = new Word(displayWords.get(display), start, end);
            } else {
                target.start = Math.min(target.start, start);
                target.end = Math.max(target.end, end);
            }

            confidences.get(display).add(result.score());

            for (CtcAligner.Result c : charGroups.get(a)) {
                double charStart = Math.max(wordStart(c), lineStart), charEnd = Math.min(wordEnd(c), lineEnd);
                if (charEnd > charStart) target.chars.add(new Char(c.text(), charStart, charEnd, c.score()));
            }
        }

        List<Word> output = new ArrayList<>();

        for (int i = 0; i < mapped.length; i++) {
            Word word = mapped[i];
            if (word == null) continue;

            word.confidence = confidences.get(i).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            word.chars.sort((a, b) -> Double.compare(a.start(), b.start()));
            output.add(word);
        }

        return output;
    }

    private double wordStart(CtcAligner.Result result) {
        return tuning.rawWordEdges ? result.rawStart() : result.start();
    }

    private double wordEnd(CtcAligner.Result result) {
        return tuning.rawWordEdges ? result.rawEnd() : result.end();
    }

    private static List<CtcAligner.Result> alignWords(float[][] emissions, List<String> words, double offset, float star) {
        List<int[]> tokens = new ArrayList<>();
        for (String word : words) tokens.add(word.chars().map(c -> AlignmentModel.token((char) c)).toArray());

        return shift(CtcAligner.align(emissions, words, tokens, AlignmentModel.FRAME_MS, CTC_MERGE_THRESHOLD_MS, star), offset);
    }

    private static List<CtcAligner.Result> alignCharacters(float[][] emissions, List<String> words, double offset, float star) {
        String text = String.join(" ", words);
        List<String> chunks = new ArrayList<>();
        List<int[]> tokens = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            chunks.add(String.valueOf(c));
            tokens.add(c == ' ' ? new int[0] : new int[] {AlignmentModel.token(c)});
        }

        return shift(CtcAligner.align(emissions, chunks, tokens, AlignmentModel.FRAME_MS, CTC_MERGE_THRESHOLD_MS, star), offset);
    }

    private static List<CtcAligner.Result> shift(List<CtcAligner.Result> results, double offset) {
        List<CtcAligner.Result> shifted = new ArrayList<>(results.size());
        for (CtcAligner.Result r : results) shifted.add(r.shifted(offset));

        return shifted;
    }

    private List<Word> repairSuspiciousRegions(List<Word> input, double lineStart, double lineEnd) throws InterruptedException {
        if (input.size() < 2) return input;

        List<Word> words = copy(input);
        List<Integer> suspicious = new ArrayList<>();

        for (int i = 0; i < words.size() - 1; i++) {
            double gap = words.get(i + 1).start - words.get(i).end;
            if (gap >= REPAIR_GAP_MS && !hasStrongPausePunctuation(words.get(i).text)) suspicious.add(i);
        }

        if (suspicious.isEmpty()) return words;

        debug("Suspicious gaps: " + suspicious.size());

        for (int gapIndex : suspicious) {
            if (gapIndex >= words.size() - 1) continue;

            double oldGap = words.get(gapIndex + 1).start - words.get(gapIndex).end;
            if (oldGap < REPAIR_GAP_MS) continue;

            int startIndex = Math.max(0, gapIndex - REPAIR_CONTEXT_BEFORE_WORDS);
            int endIndex = Math.min(words.size() - 1, gapIndex + REPAIR_CONTEXT_AFTER_WORDS);

            List<String> local = new ArrayList<>();
            for (int i = startIndex; i <= endIndex; i++) local.add(words.get(i).text);

            double localStart = Math.max(lineStart, words.get(startIndex).start - REPAIR_PADDING_MS);
            double localEnd = Math.min(lineEnd, words.get(endIndex).end + REPAIR_PADDING_MS);

            debug("  Repairing gap after '" + words.get(gapIndex).text + "' (" + Math.round(oldGap) + "ms): " + String.join(" ", local));

            List<Word> repaired;

            try {
                repaired = runAlignment(local, localStart, localEnd, lineStart, lineEnd);
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                debug("    Repair rejected: " + error);
                continue;
            }

            if (repaired.size() != local.size()) {
                debug("    Repair rejected: incomplete local alignment");
                continue;
            }

            int localGap = gapIndex - startIndex;
            if (localGap < 0 || localGap >= repaired.size() - 1) continue;

            double newGap = repaired.get(localGap + 1).start - repaired.get(localGap).end;
            double improvement = oldGap - newGap;
            double leftDrift = Math.abs(repaired.get(0).start - words.get(startIndex).start);
            double rightDrift = endIndex > gapIndex + 1 ? Math.abs(repaired.get(repaired.size() - 1).start - words.get(endIndex).start) : 0;

            if (leftDrift > REPAIR_ANCHOR_TOLERANCE_MS) {
                debug("    Repair rejected: left anchor drifted " + Math.round(leftDrift) + "ms");
                continue;
            }

            if (endIndex > gapIndex + 1 && rightDrift > REPAIR_ANCHOR_TOLERANCE_MS) {
                debug("    Repair rejected: right anchor drifted " + Math.round(rightDrift) + "ms");
                continue;
            }

            if (improvement < REPAIR_MIN_IMPROVEMENT_MS) {
                debug("    Repair rejected: only improved gap by " + Math.round(improvement) + "ms");
                continue;
            }

            int replaceStart = gapIndex + 1;
            int replaceEnd = endIndex < words.size() - 1 || endIndex > gapIndex + 1 ? endIndex - 1 : endIndex;
            if (replaceEnd < replaceStart) replaceEnd = replaceStart;

            for (int global = replaceStart; global <= replaceEnd; global++) {
                int localIndex = global - startIndex;
                if (localIndex >= 0 && localIndex < repaired.size()) words.set(global, repaired.get(localIndex));
            }

            debug("    Repair accepted: " + Math.round(oldGap) + "ms -> " + Math.round(newGap) + "ms");
        }

        return words;
    }

    private List<Word> repairLateLineStart(List<Word> words, double lineStart, double lineEnd) {
        if (words.isEmpty()) return words;

        double firstStart = words.get(0).start, delay = firstStart - lineStart;
        if (delay < LATE_LINE_START_MS) return words;

        double windowEnd = Math.min(lineEnd, lineStart + LATE_LINE_ONSET_WINDOW_MS);
        MusicAnalysis.Event anchor = null;

        for (MusicAnalysis.Event onset : analysis.vocalOnsets) {
            if (onset.time() < lineStart || onset.time() > windowEnd || onset.strength() < LATE_LINE_MIN_ONSET_SCORE) continue;
            if (anchor == null || onset.strength() > anchor.strength()) anchor = onset;
        }

        if (anchor == null || firstStart - anchor.time() < 250) return words;

        double shift = anchor.time() - firstStart;
        log.accept("Late line start repair: first word was " + Math.round(delay) + "ms late; anchoring to vocal onset at " + Math.round(anchor.time()) + "ms (shift " + Math.round(shift) + "ms)");

        List<Word> repaired = copy(words);

        for (Word word : repaired) {
            word.start = Math.max(lineStart, word.start + shift);
            word.end = Math.min(lineEnd, Math.max(word.start + 1, word.end + shift));

            List<Char> chars = new ArrayList<>(word.chars.size());

            for (Char c : word.chars) {
                double start = Math.max(lineStart, c.start() + shift);
                chars.add(new Char(c.text(), start, Math.min(lineEnd, Math.max(start + 1, c.end() + shift)), c.score()));
            }

            word.chars = chars;
        }

        return repaired;
    }

    static List<Word> smoothWords(List<Word> input, double lineStart, double lineEnd) {
        return smoothWords(input, lineStart, lineEnd, new AlignmentTuning());
    }

    static List<Word> smoothWords(List<Word> input, double lineStart, double lineEnd, AlignmentTuning tuning) {
        List<Word> words = copy(input);
        if (words.isEmpty()) return words;

        for (Word word : words) word.start = Math.max(lineStart, word.start - tuning.earlyStartMs);

        for (int i = 0; i < words.size() - 1; i++) {
            Word current = words.get(i), next = words.get(i + 1);
            double gap = next.start - current.end;

            if (gap >= 0 && gap <= tuning.maxFlowGapMs) {
                double boundary = current.end + gap * tuning.flowBoundaryPosition;
                current.end = boundary;
                next.start = boundary;
            } else if (gap > tuning.maxFlowGapMs) {
                current.end = Math.min(current.end + PAUSE_END_EXTENSION_MS, next.start);
            } else {
                double boundary = next.start + (current.end - next.start) * OVERLAP_BOUNDARY_POSITION;
                boundary = Math.min(Math.max(boundary, current.start), Math.min(current.end, next.end));

                current.end = boundary;
                next.start = boundary;
            }
        }

        Word last = words.get(words.size() - 1);
        last.end = Math.min(lineEnd, last.end + 100);

        for (int i = 0; i < words.size(); i++) {
            Word word = words.get(i);
            word.start = Math.max(word.start, lineStart);
            word.end = Math.min(word.end, lineEnd);

            if (i > 0) word.start = Math.max(word.start, words.get(i - 1).end);
            if (word.end <= word.start) word.end = Math.min(lineEnd, word.start + 1);
        }

        return words;
    }

    static List<Word> fallbackWordTimings(List<String> displayWords, double lineStart, double lineEnd) {
        List<Word> words = new ArrayList<>();
        if (displayWords.isEmpty()) return words;

        double length = Math.max(lineEnd - lineStart, 1);
        double[] weights = new double[displayWords.size()];
        double total = 0;

        for (int i = 0; i < weights.length; i++) total += weights[i] = Math.max(1, Math.pow(normalizedToken(displayWords.get(i)).length(), 0.7));

        double position = lineStart;

        for (int i = 0; i < weights.length; i++) {
            double end = i == weights.length - 1 ? lineEnd : position + length * weights[i] / total;
            Word word = new Word(displayWords.get(i), position, end);
            word.fallback = true;
            words.add(word);
            position = end;
        }

        return words;
    }

    List<String> spellingParts(String word) {
        Matcher match = SPELLING.matcher(word);
        if (!match.matches()) return List.of(word);

        String prefix = match.group(1), core = match.group(2), suffix = match.group(3);
        if (core.isEmpty()) return List.of(word);

        List<String> parts = new ArrayList<>(hyphenator.pieces(core));
        parts.removeIf(String::isEmpty);
        if (parts.size() <= 1) return List.of(word);

        parts.set(0, prefix + parts.get(0));
        parts.set(parts.size() - 1, parts.get(parts.size() - 1) + suffix);

        return parts;
    }

    private Candidates candidateSyllables(Word word) {
        List<String> parts = spellingParts(word.text);
        List<Syllable> whole = List.of(new Syllable(word.text, word.start, word.end));
        if (parts.size() <= 1) return new Candidates(whole, List.of());

        List<Char> chars = new ArrayList<>();
        for (Char c : word.chars)
            if (!normalizedToken(c.text()).isEmpty()) chars.add(c);

        int totalLength = normalizedToken(word.text).length();
        if (chars.size() != totalLength) chars = List.of();

        List<Double> boundaries = new ArrayList<>(List.of(word.start));
        List<Boundary> metadata = new ArrayList<>();
        int cumulative = 0;

        for (int p = 0; p < parts.size() - 1; p++) {
            cumulative += normalizedToken(parts.get(p)).length();

            double boundary, charGap = 0;
            boolean characterTiming = false;

            if (!chars.isEmpty() && cumulative > 0 && cumulative < chars.size()) {
                Char left = chars.get(cumulative - 1), right = chars.get(cumulative);
                charGap = Math.max(0, right.start() - left.end());
                boundary = right.start() >= left.end() ? left.end() + (right.start() - left.end()) * tuning.characterBoundaryPosition : (left.start() + right.start()) / 2;
                characterTiming = true;
            } else {
                boundary = word.start + (word.end - word.start) * (cumulative / (double) Math.max(totalLength, 1));
            }

            int remaining = parts.size() - (p + 1);
            double earliest = boundaries.get(boundaries.size() - 1) + MIN_SYLLABLE_MS, latest = word.end - remaining * MIN_SYLLABLE_MS;

            boundary = refineTowardOnset(boundary, earliest, latest, tuning.onsetRefinementWindowMs);
            boundary = Math.min(Math.max(boundary, earliest), latest);

            boundaries.add(boundary);
            metadata.add(new Boundary(boundary, charGap, characterTiming, parts.get(p).endsWith("-")));
        }

        boundaries.add(word.end);

        List<Syllable> syllables = new ArrayList<>();

        for (int i = 0; i < parts.size(); i++) {
            double start = boundaries.get(i), end = boundaries.get(i + 1);
            if (end <= start) return new Candidates(whole, List.of());

            syllables.add(new Syllable(parts.get(i), start, end));
        }

        return new Candidates(syllables, metadata);
    }

    double refineTowardOnset(double time, double earliest, double latest, double windowMs) {
        if (windowMs <= 0 || analysis.vocalOnsets.isEmpty()) return time;

        MusicAnalysis.Event best = null;
        double bestScore = 0;
        int index = Arrays.binarySearch(analysis.vocalOnsetTimes, time - windowMs);
        if (index < 0) index = -index - 1;

        for (int i = index; i < analysis.vocalOnsets.size(); i++) {
            MusicAnalysis.Event onset = analysis.vocalOnsets.get(i);
            if (onset.time() > time + windowMs) break;
            if (onset.time() < earliest || onset.time() > latest || onset.strength() < tuning.onsetRefinementMinimumStrength) continue;

            double proximity = Math.exp(-0.5 * Math.pow((onset.time() - time) / (windowMs / 2), 2));
            double score = onset.strength() * proximity;

            if (score > bestScore) {
                bestScore = score;
                best = onset;
            }
        }

        if (best == null) return time;

        double confidence = clamp01((best.strength() - tuning.onsetRefinementMinimumStrength) / (1 - tuning.onsetRefinementMinimumStrength));
        double proximity = Math.exp(-0.5 * Math.pow((best.time() - time) / (windowMs / 2), 2));
        double weight = tuning.onsetRefinementWeight * Math.max(confidence, 0.5) * proximity;

        return time + (best.time() - time) * weight;
    }

    private List<Word> refineWordStarts(List<Word> input, double lineStart) {
        if (tuning.wordOnsetWindowMs <= 0) return input;

        List<Word> words = copy(input);
        double previousStart = lineStart;

        for (Word word : words) {
            if (!word.fallback) {
                double refined = refineTowardOnset(word.start, Math.max(lineStart, previousStart + MIN_SYLLABLE_MS), word.end - MIN_SYLLABLE_MS, tuning.wordOnsetWindowMs);
                if (refined != word.start) debug("    word start '" + word.text + "' " + Math.round(word.start) + " -> " + Math.round(refined) + " (vocal onset)");
                word.start = refined;
            }

            previousStart = word.start;
        }

        return words;
    }

    List<Syllable> musicalSyllables(Word word) {
        Candidates candidates = candidateSyllables(word);
        List<Syllable> syllables = candidates.syllables;
        if (syllables.size() <= 1 || candidates.boundaries.isEmpty()) return syllables;

        boolean[] split = new boolean[candidates.boundaries.size()];
        StringBuilder decisions = new StringBuilder();

        for (int i = 0; i < split.length; i++) {
            Boundary metadata = candidates.boundaries.get(i);
            double boundary = metadata.time;
            Syllable left = syllables.get(i), right = syllables.get(i + 1);

            double vocalPeak = nearestWeightedEvent(boundary, analysis.vocalOnsets, analysis.vocalOnsetTimes, VOCAL_ONSET_WINDOW_MS).score;
            double vocalScore = Math.max(vocalPeak, localEnvelopeScore(boundary, analysis.vocalEnvelope, VOCAL_ONSET_WINDOW_MS) * 0.72);

            double instrumentalPeak = nearestWeightedEvent(boundary, analysis.instrumentalOnsets, analysis.instrumentalOnsetTimes, INSTRUMENTAL_ONSET_WINDOW_MS).score;
            double instrumentalScore = Math.max(instrumentalPeak, localEnvelopeScore(boundary, analysis.instrumentalEnvelope, INSTRUMENTAL_ONSET_WINDOW_MS) * 0.60);

            double rhythmScore = nearestWeightedEvent(boundary, analysis.rhythmEvents, analysis.rhythmEventTimes, RHYTHM_TOLERANCE_MS).score;

            double shortestSide = Math.min(left.end() - left.start(), right.end() - right.start());
            double durationScore = clamp01(shortestSide / 180.0);
            double ctcGapScore = clamp01(metadata.charGap / CTC_GAP_FULL_SCORE_MS);

            double baseScore = vocalScore * VOCAL_SCORE_WEIGHT + rhythmScore * RHYTHM_SCORE_WEIGHT + instrumentalScore * INSTRUMENTAL_SCORE_WEIGHT + durationScore * DURATION_SCORE_WEIGHT;

            boolean ctcSupport = metadata.characterTiming && ctcGapScore >= MIN_CTC_SPLIT_SCORE && rhythmScore >= MIN_CTC_SUPPORT_RHYTHM_SCORE && (vocalScore >= MIN_VOCAL_SPLIT_SCORE || instrumentalScore >= MIN_CTC_SUPPORT_INSTRUMENTAL_SCORE);

            double totalScore = clamp01(baseScore + (ctcSupport ? ctcGapScore * CTC_GAP_BONUS_WEIGHT : 0) + (metadata.hyphen ? HYPHEN_SPLIT_BONUS : 0));

            boolean acoustic = vocalPeak >= MIN_VOCAL_PEAK_SPLIT_SCORE || ctcSupport && vocalScore >= MIN_VOCAL_SPLIT_SCORE;

            split[i] = tuning.splitEverySyllable || metadata.hyphen || totalScore >= tuning.syllableSplitThreshold && acoustic && rhythmScore >= MIN_RHYTHM_SPLIT_SCORE && shortestSide >= MIN_SPLIT_SIDE_MS;

            decisions.append(String.format(Locale.ROOT, "%n    %-5s after '%s' score=%.3f (vocal=%.2f, peak=%.2f, rhythm=%.2f, inst=%.2f, ctc=%s, hyphen=%s, shortest=%.0fms)", split[i] ? "SPLIT" : "merge", left.text(), totalScore, vocalScore, vocalPeak, rhythmScore, instrumentalScore, ctcSupport, metadata.hyphen, shortestSide));
        }

        List<Syllable> merged = new ArrayList<>(List.of(syllables.get(0)));

        for (int i = 0; i < split.length; i++) {
            Syllable next = syllables.get(i + 1);

            if (split[i]) {
                merged.add(next);
            } else {
                Syllable last = merged.remove(merged.size() - 1);
                merged.add(new Syllable(last.text() + next.text(), last.start(), next.end()));
            }
        }

        if (merged.size() == 1) merged = List.of(new Syllable(word.text, word.start, word.end));

        debug("  " + word.text + " " + Math.round(word.start) + " -> " + Math.round(word.end) + " [" + String.join(" | ", merged.stream().map(Syllable::text).collect(java.util.stream.Collectors.toList())) + "]" + decisions);
        return merged;
    }

    private static Weighted nearestWeightedEvent(double time, List<MusicAnalysis.Event> events, double[] times, double tolerance) {
        if (events.isEmpty()) return new Weighted(0, null, -1);

        int index = Arrays.binarySearch(times, time);
        if (index < 0) index = -index - 1;
        else
            while (index > 0 && times[index - 1] == time) index--;

        double bestScore = 0, bestDistance = -1;
        MusicAnalysis.Event best = null;

        for (int i = Math.max(0, index - 3); i < Math.min(events.size(), index + 4); i++) {
            MusicAnalysis.Event event = events.get(i);
            double distance = Math.abs(event.time() - time);
            if (distance > tolerance * 2.5) continue;

            double proximity = Math.exp(-0.5 * Math.pow(distance / tolerance, 2));
            double score = event.strength() * proximity;

            if (score > bestScore) {
                bestScore = score;
                best = event;
                bestDistance = distance;
            }
        }

        return new Weighted(clamp01(bestScore), best, bestDistance);
    }

    private double localEnvelopeScore(double time, float[] envelope, double windowMs) {
        if (envelope == null || envelope.length == 0) return 0;

        double framesPerSecond = analysis.rate / (double) analysis.hop;
        long center = Math.round(time / 1000 * framesPerSecond);
        long radius = Math.max(1, (long) Math.ceil(windowMs / 1000 * framesPerSecond));

        int start = (int) Math.max(0, center - radius), end = (int) Math.min(envelope.length, center + radius + 1);
        if (end <= start) return 0;

        float max = 0;
        for (int i = start; i < end; i++) max = Math.max(max, envelope[i]);

        return clamp01(max);
    }

    private static List<Word> copy(List<Word> words) {
        List<Word> copy = new ArrayList<>(words.size());
        for (Word word : words) copy.add(word.copy());

        return copy;
    }
}
