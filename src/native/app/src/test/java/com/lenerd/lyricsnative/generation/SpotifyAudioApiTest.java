package com.lenerd.lyricsnative.generation;

import com.spotify.connectivity.httpretrofit.RetrofitMaker;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpotifyAudioApiTest {
    public enum MediaKind {
        AUDIO;
        public int getNumber() { return 1; }
    }

    public static final class StaticMessage {
        public static void renamed(StaticMessage message, MediaKind kind) { }
        public static StaticMessage changed(byte[] bytes) { return new StaticMessage(); }
    }

    public static final class InstanceMessage {
        public void different(MediaKind kind) { }
    }

    @Test
    public void discoversStaticAndInstanceGeneratedEnumSetters() throws Exception {
        assertEquals(1, SpotifyAudioApi.enumNumber(StaticMessage.class, "AUDIO"));
        assertEquals(1, SpotifyAudioApi.enumNumber(InstanceMessage.class, "AUDIO"));
        assertTrue(SpotifyAudioApi.parse(StaticMessage.class, new byte[0]) instanceof StaticMessage);
    }

    @Test
    public void wireRequestsKeepStableFieldNumbers() {
        assertArrayEquals(new byte[] {8, 1, 18, 3, 97, 98, 99}, SpotifyAudioApi.message(1, 1, 2, "abc"));
        assertArrayEquals(new byte[] {10, 2, 24, 1}, SpotifyAudioApi.message(1, SpotifyAudioApi.message(3, 1)));
    }

    @Test
    public void discoversRenamedHttpMembersAndCancelsRequest() throws Exception {
        RetrofitMaker maker = new RetrofitMaker();
        AtomicLong epoch = new AtomicLong(1);
        try (SpotifyResolverCall call = SpotifyResolverCall.metadata(maker, "0000000000000000000000", epoch::get)) {
            assertArrayEquals(new byte[] {123, 125}, call.metadataOnWorker());
            assertEquals("https://spclient.wg.spotify.com/metadata/4/track/00000000000000000000000000000000?market=from_token", maker.observedClient.lastCall.request.builder.destination.toString());
            assertEquals("application/json", maker.observedClient.lastCall.request.builder.accept);
            assertEquals(20, maker.observedClient.lastCall.deadline.seconds);
        }
        assertTrue(maker.observedClient.lastCall.cancelled);
    }

    @Test
    public void changedEpochCancelsBeforeExecution() throws Exception {
        RetrofitMaker maker = new RetrofitMaker();
        AtomicLong epoch = new AtomicLong(1);
        try (SpotifyResolverCall call = SpotifyResolverCall.metadata(maker, "0000000000000000000000", epoch::get)) {
            epoch.set(3);
            assertThrows(InterruptedIOException.class, call::metadataOnWorker);
            assertTrue(maker.observedClient.lastCall.cancelled);
        }
    }
}
