package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicReference;

final class RemoteSongHandleCall implements AutoCloseable {
    private final LongSupplier currentEpoch;
    private final long epoch;
    private final CompletableFuture<Integer> result = new CompletableFuture<>();
    private volatile Object subscription;
    private volatile boolean closed;

    RemoteSongHandleCall(LongSupplier currentEpoch, long epoch) {
        this.currentEpoch = currentEpoch;
        this.epoch = epoch;
    }

    int execute(SpotifyPlusContext context, byte[] fileId) throws Exception {
        if (fileId == null || fileId.length != 20) throw new IOException("Invalid audio file identifier");
        requireCurrent();
        Class<?> parameters = context.getHooks().findClass("com.spotify.download.esperanto.proto.EsDownload$GetMediaHandleParams");
        byte[] request = SpotifyAudioApi.message(1, SpotifyAudioApi.enumNumber(parameters, "AUDIO"), 2, RootTrackFiles.hex(fileId));
        Object stream = SpotifyReflection.call(SpotifyAudioApi.transport(context), "callStream", "spotify.download.esperanto.proto.Download", "GetMediaHandleId", request);
        stream = SpotifyReflection.call(stream, "timeout", 30L, TimeUnit.SECONDS);
        Class<?> consumer = context.getHooks().findClass("io.reactivex.rxjava3.functions.Consumer");
        Class<?> parser = context.getHooks().findClass("com.spotify.download.esperanto.proto.EsDownload$GetMediaHandleIdResponse");
        AtomicReference<Integer> initialHandle = new AtomicReference<>();
        Object next = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[] {consumer}, (proxy, method, args) -> {
            if (!method.getName().equals("accept")) return objectMethod(proxy, method.getName(), args);
            byte[] bytes = args != null && args.length == 1 && args[0] instanceof byte[] value && value.length <= 65536 ? value.clone() : null;
            try {
                requireCurrent();
                if (bytes == null || bytes.length > 65536) throw new IOException("Invalid audio initialization response");
                Object response = SpotifyAudioApi.parse(parser, bytes);
                initialHandle.compareAndSet(null, ((Number) SpotifyAudioApi.field(response, "handleId_")).intValue());
                if ((((Number) SpotifyAudioApi.field(response, "bitField0_")).intValue() & 1) == 0) return null;
                int status = ((Number) SpotifyAudioApi.field(response, "status_")).intValue();
                if (status == 0) result.complete(initialHandle.get());
                else result.completeExceptionally(new IOException("Audio initialization rejected"));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            } finally {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
            }
            return null;
        });
        Object error = Proxy.newProxyInstance(consumer.getClassLoader(), new Class<?>[] {consumer}, (proxy, method, args) -> {
            if (!method.getName().equals("accept")) return objectMethod(proxy, method.getName(), args);
            result.completeExceptionally(new IOException("Audio initialization failed"));
            return null;
        });
        try {
            subscription = SpotifyReflection.call(stream, "subscribe", next, error);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                requireCurrent();
                try {
                    int handle = result.get(100, TimeUnit.MILLISECONDS);
                    requireCurrent();
                    return handle;
                } catch (TimeoutException ignored) { }
            }
            throw new IOException("Audio initialization timed out");
        } finally {
            close();
        }
    }

    private void requireCurrent() throws IOException {
        if (closed || Thread.currentThread().isInterrupted() || epoch == 0 || currentEpoch.getAsLong() != epoch) throw new InterruptedIOException("Audio generation cancelled");
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return args != null && args.length == 1 && proxy == args[0];
        if (name.equals("toString")) return "RemoteSongHandleObserver";
        return null;
    }

    @Override
    public void close() {
        closed = true;
        Object disposable = subscription;
        subscription = null;
        if (disposable != null) try {
                SpotifyReflection.call(disposable, "dispose");
            } catch (Exception ignored) { }
    }
}
