package com.spotify.contentdelivery.mediaprocessing;

public abstract class CreateProcessorResult {
    public static final class Success extends CreateProcessorResult {
        private final Object processor;

        public Success(Object processor) { this.processor = processor; }

        public Object getProcessor() { return processor; }
    }

    public static final class Failure extends CreateProcessorResult { }
}
