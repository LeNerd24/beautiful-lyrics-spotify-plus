package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.hooks.HookHandle;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class RemoteScopeLease implements AutoCloseable {
    private final SpotifyPlusContext context;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private final ArrayList<HookHandle> hooks = new ArrayList<>();
    private volatile boolean closed;

    boolean alive() { return !closed; }

    void verifyFactory() throws Exception {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IOException("Audio scope closed");
            Object scope = AuthenticatedScopeAudit.find(context);
            if (scope == null || ((Number) SpotifyReflection.call(scope, "getNThis")).longValue() == 0) throw new IOException("Authenticated audio scope unavailable");
            Object factory = SpotifyReflection.call(scope, "mediaProcessorFactory");
            if (factory == null || ((Number) SpotifyReflection.call(factory, "getNThis")).longValue() == 0) throw new IOException("Media factory unavailable");
            if (factory != SpotifyReflection.call(scope, "mediaProcessorFactory")) throw new IOException("Unexpected factory ownership");
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    RemoteScopeLease(SpotifyPlusContext context) throws Exception {
        this.context = context;
        try {
            for (String name : new String[] {"com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl", "com.spotify.core.coreimpl.NativeApplicationScopeImpl", "com.spotify.concurrency.asyncimpl.NativeTimerManagerThreadImpl"}) {
                int count = 0;
                for (Method method : context.getHooks().findClass(name).getDeclaredMethods()) {
                    if (!Set.of("prepareForShutdown", "destroy", "stop", "kill").contains(method.getName())) continue;
                    hooks.add(context.getHooks().before(method, call -> invalidate()));
                    count++;
                }
                if (count == 0) throw new IOException("Audio scope guard unavailable");
            }
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    void capture(int handle, long epoch) throws Exception {
        lifecycle.readLock().lock();
        Object processor = null;
        try {
            if (closed) throw new IOException("Audio scope closed");
            Object scope = AuthenticatedScopeAudit.find(context);
            if (scope == null || ((Number) SpotifyReflection.call(scope, "getNThis")).longValue() == 0) throw new IOException("Authenticated audio scope unavailable");
            Object factory = SpotifyReflection.call(scope, "mediaProcessorFactory");
            if (factory == null || ((Number) SpotifyReflection.call(factory, "getNThis")).longValue() == 0) throw new IOException("Media factory unavailable");
            Object mode = null;
            for (Object value : context.getHooks().findClass("com.spotify.contentdelivery.mediaprocessing.ProcessingMode").getEnumConstants())
                if (((Enum<?>) value).name().equals("STREAM")) mode = value;
            if (mode == null || !RemoteAudioNative.arm(epoch, handle)) throw new IOException("Owned initialization capture unavailable");
            android.util.Log.i("LyricsSpotifyAudio", "Current recording initialization armed");
            Object result = SpotifyReflection.call(factory, "createProcessor", handle, mode);
            Class<?> processorType = context.getHooks().findClass("com.spotify.contentdelivery.mediaprocessing.NativeMediaProcessor");
            processor = MediaProcessorResult.unwrap(result, processorType);
            int status = RemoteAudioNative.status(epoch);
            if (processor == null || status != 2) throw new IOException("Owned initialization capture rejected (processor=" + (result == null ? "null" : result.getClass().getSimpleName()) + ", status=" + status + ")");
        } finally {
            try {
                if (processor != null) SpotifyReflection.call(processor, "destroy");
            } finally {
                lifecycle.readLock().unlock();
            }
        }
    }

    byte[] decrypt(long epoch, byte[] input) throws IOException {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IOException("Audio scope closed");
            byte[] result = RemoteAudioNative.decrypt(epoch, input);
            if (result == null) throw new IOException("Independent audio decryption failed");
            return result;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void invalidate() {
        lifecycle.writeLock().lock();
        try {
            closed = true;
            RemoteAudioNative.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        invalidate();
        for (HookHandle hook : hooks) hook.unhook();
        hooks.clear();
    }
}
