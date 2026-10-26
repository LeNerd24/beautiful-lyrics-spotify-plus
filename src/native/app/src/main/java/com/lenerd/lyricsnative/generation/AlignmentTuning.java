package com.lenerd.lyricsnative.generation;

final class AlignmentTuning {
    float starLogProbability = -0.5f;
    float characterStarLogProbability = 0f;

    boolean rawWordEdges = false;

    double frameOffsetMs = 0;

    double characterBoundaryPosition = 0.5;

    double onsetRefinementWindowMs = 0;
    double onsetRefinementMinimumStrength = 0.35;
    double onsetRefinementWeight = 0.65;

    double wordOnsetWindowMs = 0;

    double syllableSplitThreshold = 0.66;

    double maxFlowGapMs = 280;

    double flowBoundaryPosition = 0.42;

    double earlyStartMs = 25;

    boolean splitEverySyllable = false;

    boolean keepArticulatedGaps = false;

    AlignmentTuning copy() {
        AlignmentTuning copy = new AlignmentTuning();
        copy.starLogProbability = starLogProbability;
        copy.characterStarLogProbability = characterStarLogProbability;
        copy.rawWordEdges = rawWordEdges;
        copy.frameOffsetMs = frameOffsetMs;
        copy.characterBoundaryPosition = characterBoundaryPosition;
        copy.onsetRefinementWindowMs = onsetRefinementWindowMs;
        copy.onsetRefinementMinimumStrength = onsetRefinementMinimumStrength;
        copy.onsetRefinementWeight = onsetRefinementWeight;
        copy.wordOnsetWindowMs = wordOnsetWindowMs;
        copy.syllableSplitThreshold = syllableSplitThreshold;
        copy.maxFlowGapMs = maxFlowGapMs;
        copy.flowBoundaryPosition = flowBoundaryPosition;
        copy.earlyStartMs = earlyStartMs;
        copy.splitEverySyllable = splitEverySyllable;
        copy.keepArticulatedGaps = keepArticulatedGaps;
        return copy;
    }
}
