package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class SpotifyAudioApi {
    static Object transport(SpotifyPlusContext context) throws Exception {
        Object core = Class.forName("com.lenerd.spotifyplus.module.hooks.PlayerHook", false, SpotifyAudioApi.class.getClassLoader()).getField("core").get(null);
        if (core == null) throw new IllegalStateException("Spotify player unavailable");
        Class<?> base = context.getHooks().findClass("com.spotify.esperanto.esperanto.ClientBase");
        Object client = null;
        for (Class<?> type = core.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !base.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(core);
                if (value != null && client != null && client != value) throw new IllegalStateException("Ambiguous Spotify client");
                if (value != null) client = value;
            }
        }
        if (client == null) throw new IllegalStateException("Spotify client unavailable");
        return field(client, "transport");
    }

    static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Object parse(Class<?> type, byte[] payload) throws Exception {
        Method parser = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != type || !Arrays.equals(method.getParameterTypes(), new Class<?>[] {byte[].class})) continue;
            if (parser != null) throw new NoSuchMethodException("Ambiguous protobuf parser: " + type.getName());
            parser = method;
        }
        if (parser == null) throw new NoSuchMethodException("Protobuf parser: " + type.getName());
        return parser.invoke(null, payload);
    }

    static int enumNumber(Class<?> message, String name) throws Exception {
        Integer result = null;
        for (Method method : message.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            Class<?> enumType = null;
            if (Modifier.isStatic(method.getModifiers()) && parameters.length == 2 && parameters[0] == message && parameters[1].isEnum()) enumType = parameters[1];
            if (!Modifier.isStatic(method.getModifiers()) && parameters.length == 1 && parameters[0].isEnum()) enumType = parameters[0];
            if (enumType == null) continue;
            for (Object value : enumType.getEnumConstants()) {
                if (!((Enum<?>) value).name().equals(name)) continue;
                int number = ((Number) SpotifyReflection.call(value, "getNumber")).intValue();
                if (result != null && result != number) throw new IllegalStateException("Ambiguous protobuf enum");
                result = number;
            }
        }
        if (result == null) throw new NoSuchFieldException("Protobuf enum: " + name);
        return result;
    }

    static byte[] message(Object... fields) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int i = 0; i < fields.length; i += 2) {
            int number = (Integer) fields[i];
            Object value = fields[i + 1];
            if (value instanceof Number scalar) {
                varint(result, (long) number << 3);
                varint(result, scalar.longValue());
            } else {
                byte[] bytes = value instanceof byte[] data ? data : value.toString().getBytes(StandardCharsets.UTF_8);
                varint(result, ((long) number << 3) | 2);
                varint(result, bytes.length);
                result.write(bytes, 0, bytes.length);
            }
        }
        return result.toByteArray();
    }

    private static void varint(ByteArrayOutputStream result, long value) {
        while ((value & ~127L) != 0) {
            result.write((int) value & 127 | 128);
            value >>>= 7;
        }
        result.write((int) value);
    }

    static Object optional(Object value) throws Exception {
        Class<?> helper = Class.forName("com.lenerd.spotifyplus.module.symbols.Optionals", false, SpotifyAudioApi.class.getClassLoader());
        return helper.getMethod("get", Object.class).invoke(null, value);
    }

    private SpotifyAudioApi() { }
}
