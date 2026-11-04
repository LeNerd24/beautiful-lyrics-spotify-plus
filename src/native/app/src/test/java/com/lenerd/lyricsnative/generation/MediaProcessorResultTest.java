package com.lenerd.lyricsnative.generation;

import com.spotify.contentdelivery.mediaprocessing.CreateProcessorResult;
import org.junit.Test;

import static org.junit.Assert.*;

public final class MediaProcessorResultTest {
    private static final class Processor { }

    @Test
    public void acceptsDirectProcessorFromOlderFactories() throws Exception {
        Processor processor = new Processor();
        assertSame(processor, MediaProcessorResult.unwrap(processor, Processor.class));
    }

    @Test
    public void unwrapsSuccessFromNewerFactories() throws Exception {
        Processor processor = new Processor();
        assertSame(processor, MediaProcessorResult.unwrap(new CreateProcessorResult.Success(processor), Processor.class));
    }

    @Test
    public void rejectsMissingFailedAndUnexpectedProcessors() throws Exception {
        assertNull(MediaProcessorResult.unwrap(null, Processor.class));
        assertNull(MediaProcessorResult.unwrap(new Object(), Processor.class));
        assertNull(MediaProcessorResult.unwrap(new CreateProcessorResult.Failure(), Processor.class));
        assertNull(MediaProcessorResult.unwrap(new CreateProcessorResult.Success(null), Processor.class));
        assertNull(MediaProcessorResult.unwrap(new CreateProcessorResult.Success(new Object()), Processor.class));
    }
}
