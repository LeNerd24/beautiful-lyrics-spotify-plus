package com.lenerd.lyricsnative.sing;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

final class SingAudioContract {
    static Map<String, Method> resolve(Class<?> driver, Class<?> audioTrack) throws ReflectiveOperationException {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (String name : new String[] {"write", "getQueuedSize", "getPosition", "getFreeSize", "getAudioTrack", "trackHasEnded", "flush", "destroy", "startPlayback"}) {
            Class<?> result = switch (name) {
                case "write", "getQueuedSize", "getPosition", "getFreeSize" -> int.class;
                case "getAudioTrack" -> audioTrack;
                default -> void.class;
            };
            Method method = driver.getDeclaredMethod(name, name.equals("write") ? new Class<?>[] {byte[].class, int.class} : new Class<?>[0]);
            if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) throw new NoSuchMethodException("Unsupported Spotify audio contract: " + method);
            method.setAccessible(true);
            methods.put(name, method);
        }
        return methods;
    }

    private SingAudioContract() { }
}
