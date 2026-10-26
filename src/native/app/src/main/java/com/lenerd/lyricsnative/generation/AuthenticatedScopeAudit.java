package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import android.util.Log;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

final class AuthenticatedScopeAudit {
    static Object find(SpotifyPlusContext context) throws Exception {
        Class<?> target = context.getHooks().findClass("com.spotify.core.corefullimpl.NativeFullAuthenticatedScopeImpl");
        try {
            Object application = context.getActivity().getApplication();
            Map<?, ?> injectors = (Map<?, ?>) field(field(application, "a"), "a");
            Object component = field(injectors.get("com.spotify.accountswitching.accountpickerimpl.AccountPickerActivity"), "b");
            Object scope = fromComponent(component);
            if (target.isInstance(scope)) return scope;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.i("LyricsSpotifyAudio", "Audio scope unavailable: " + error.getClass().getSimpleName());
        }
        return null;
    }

    private static Object fromComponent(Object component) {
        try {
            Object provider = field(field(component, "H3"), "a"), controller = field(provider, "b");
            Object state = field(field(controller, "i"), "i"), ready = field(state, "a");
            if (!Set.of("p.h7h0", "p.l7h0").contains(ready.getClass().getName())) return null;
            Object scope = field(ready, "d");
            return field(scope.getClass().getMethod("e").invoke(scope), "W0");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.i("LyricsSpotifyAudio", "Audio scope component unavailable: " + error.getClass().getSimpleName());
            return null;
        }
    }

    private static Object field(Object value, String name) throws ReflectiveOperationException {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(value);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private AuthenticatedScopeAudit() { }
}
