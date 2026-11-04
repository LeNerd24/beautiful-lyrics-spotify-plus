package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

final class AuthenticatedScopeAudit {
    private static WeakReference<Object> cached = new WeakReference<>(null);

    static synchronized Object find(SpotifyPlusContext context) throws Exception {
        Class<?> target = context.getHooks().findClass("com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl");
        Object previous = cached.get();
        if (target.isInstance(previous) && ((Number) SpotifyReflection.call(previous, "getNThis")).longValue() != 0) return previous;
        ArrayDeque<Object> pending = new ArrayDeque<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Object application = context.getActivity().getApplication();
        Object injector = accountInjector(application);
        Log.i("LyricsSpotifyAudio", "Inspecting retained audio scope from " + (injector == null ? application : injector).getClass().getName());
        enqueue(pending, seen, injector == null ? application : injector);
        int inspected = 0;
        while (!pending.isEmpty() && inspected++ < 131072) {
            Object value = pending.removeFirst();
            if (target.isInstance(value)) {
                if (((Number) SpotifyReflection.call(value, "getNThis")).longValue() == 0) continue;
                cached = new WeakReference<>(value);
                return value;
            }
            if (value instanceof java.util.concurrent.atomic.AtomicReference<?> reference) {
                enqueue(pending, seen, reference.get());
                continue;
            }
            if (value instanceof java.lang.ref.Reference<?> reference) {
                enqueue(pending, seen, reference.get());
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                for (Object child : map.values()) enqueue(pending, seen, child);
                continue;
            }
            if (value instanceof Iterable<?> items) {
                for (Object child : items) enqueue(pending, seen, child);
                continue;
            }
            if (value instanceof Object[] items) {
                for (Object child : items) enqueue(pending, seen, child);
                continue;
            }
            String name = value.getClass().getName();
            if (!name.startsWith("p.") && !name.startsWith("com.spotify.") && !name.startsWith("dagger.") && !name.startsWith("kotlin.") && !name.startsWith("kotlinx.") && !name.startsWith("io.reactivex.")) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        enqueue(pending, seen, field.get(value));
                    } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            }
        }
        Log.i("LyricsSpotifyAudio", "Authenticated audio scope unavailable after inspecting " + seen.size() + " retained objects");
        return null;
    }

    private static Object accountInjector(Object application) {
        ArrayDeque<Object> pending = new ArrayDeque<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        enqueue(pending, seen, application);
        for (int inspected = 0; !pending.isEmpty() && inspected < 256; inspected++) {
            Object value = pending.removeFirst();
            if (value instanceof Map<?, ?> map) {
                Object injector = map.get("com.spotify.accountswitching.accountpickerimpl.AccountPickerActivity");
                if (injector != null) return injector;
                continue;
            }
            String name = value.getClass().getName();
            if (!name.startsWith("p.") && !name.startsWith("com.spotify.") && !name.startsWith("dagger.")) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        enqueue(pending, seen, field.get(value));
                    } catch (ReflectiveOperationException | RuntimeException ignored) { }
                }
            }
        }
        return null;
    }

    private static void enqueue(ArrayDeque<Object> pending, Set<Object> seen, Object value) {
        if (value == null || value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum<?> || value instanceof Class<?> || value instanceof java.lang.reflect.Member || value instanceof Thread) return;
        if (pending.size() < 131072 && seen.add(value)) pending.addLast(value);
    }

    private AuthenticatedScopeAudit() { }
}
