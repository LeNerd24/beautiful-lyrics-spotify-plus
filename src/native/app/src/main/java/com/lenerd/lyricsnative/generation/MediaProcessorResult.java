package com.lenerd.lyricsnative.generation;

final class MediaProcessorResult {
    static Object unwrap(Object result, Class<?> processorType) throws Exception {
        if (processorType.isInstance(result)) return result;
        if (result == null || !result.getClass().getName().equals("com.spotify.contentdelivery.mediaprocessing.CreateProcessorResult$Success")) return null;
        Object processor = SpotifyReflection.call(result, "getProcessor");
        return processorType.isInstance(processor) ? processor : null;
    }

    private MediaProcessorResult() { }
}
