package com.lenerd.lyricsnative.generation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class SpotifyReflection {
    static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class<?> value ? value : target.getClass();
        for (Method method : type.getMethods()) {
            if (method.isBridge() || !method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> parameter = parameters[i];
                if (parameter == int.class) parameter = Integer.class;
                if (parameter == long.class) parameter = Long.class;
                if (parameter == boolean.class) parameter = Boolean.class;
                if (!parameter.isInstance(args[i])) match = false;
            }
            if (!match) continue;
            try {
                return method.invoke(target instanceof Class<?> ? null : target, args);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                if (error.getCause() instanceof Error cause) throw cause;
                throw error;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private SpotifyReflection() { }
}
