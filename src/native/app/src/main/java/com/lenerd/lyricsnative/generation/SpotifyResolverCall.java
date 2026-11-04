package com.lenerd.lyricsnative.generation;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import com.lenerd.spotifyplus.sdk.hooks.SpotifyPlusHooks;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

final class SpotifyResolverCall implements AutoCloseable {
    private final Object call;
    private final Method execute, cancel, bodyStream, bodyLength;
    private final Field status, body;
    private final StorageResolution.Request request;
    private final LongSupplier currentEpoch;
    private final long epoch;
    private final int bodyLimit;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();

    SpotifyResolverCall(Object maker, StorageResolution.Request request, LongSupplier currentEpoch) throws ReflectiveOperationException, IOException {
        this(maker, request, currentEpoch, null, 65_536, null);
    }

    SpotifyResolverCall(Object maker, StorageResolution.Request request, LongSupplier currentEpoch, SpotifyPlusHooks hooks) throws ReflectiveOperationException, IOException {
        this(maker, request, currentEpoch, null, 65_536, hooks);
    }

    static SpotifyResolverCall metadata(Object maker, String trackId, LongSupplier currentEpoch) throws ReflectiveOperationException, IOException { return new SpotifyResolverCall(maker, null, currentEpoch, trackPath(trackId), 262_144, null); }

    static SpotifyResolverCall metadata(Object maker, String trackId, LongSupplier currentEpoch, SpotifyPlusHooks hooks) throws ReflectiveOperationException, IOException { return new SpotifyResolverCall(maker, null, currentEpoch, trackPath(trackId), 262_144, hooks); }

    static String trackPath(String trackId) throws IOException {
        return "metadata/4/track/" + RootTrackFiles.hex(trackGid(trackId)) + "?market=from_token";
    }

    static byte[] trackGid(String trackId) throws IOException {
        if (trackId == null || !trackId.matches("[A-Za-z0-9]{22}")) throw new IOException("Invalid current track identifier");
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        BigInteger gid = BigInteger.ZERO;
        for (char value : trackId.toCharArray()) gid = gid.multiply(BigInteger.valueOf(62)).add(BigInteger.valueOf(alphabet.indexOf(value)));
        if (gid.bitLength() > 128) throw new IOException("Track identifier exceeds limit");
        byte[] value = gid.toByteArray(), result = new byte[16];
        int size = Math.min(value.length, result.length);
        System.arraycopy(value, value.length - size, result, result.length - size, size);
        return result;
    }

    private SpotifyResolverCall(Object maker, StorageResolution.Request request, LongSupplier currentEpoch, String path, int bodyLimit, SpotifyPlusHooks hooks) throws ReflectiveOperationException, IOException {
        this.request = request;
        this.currentEpoch = currentEpoch;
        this.bodyLimit = bodyLimit;
        epoch = currentEpoch.getAsLong();
        if ((epoch & 1) == 0) throw new InterruptedIOException("Resolver session is no longer eligible");
        if (maker == null || !maker.getClass().getName().equals("com.spotify.connectivity.httpretrofit.RetrofitMaker")) throw new IOException("Spotify HTTP client unavailable");
        Object retrofit = SpotifyAudioApi.field(maker, "mRetrofitWebgate");
        Object client = null, baseValue = null;
        for (Field field : retrofit.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(retrofit);
            if (value == null) continue;
            try {
                URI candidate = URI.create(value.toString());
                if ("https".equals(candidate.getScheme()) && "spclient.wg.spotify.com".equals(candidate.getHost())) {
                    if (baseValue != null) throw new IOException("Ambiguous Spotify HTTP base");
                    baseValue = value;
                }
            } catch (IllegalArgumentException ignored) { }
            for (Method method : value.getClass().getMethods()) {
                if (method.getParameterCount() != 1 || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
                try {
                    if (!requestShape(method.getParameterTypes()[0], null)) continue;
                    if (client != null && client != value) throw new IOException("Ambiguous Spotify HTTP client");
                    client = value;
                } catch (NoSuchMethodException ignored) { }
            }
        }
        if (client == null || baseValue == null) throw new IOException("Spotify HTTP client unavailable");
        URI base = URI.create(baseValue.toString());
        if (!"/".equals(base.getPath()) || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || (base.getPort() != -1 && base.getPort() != 443)) throw new IOException("Unexpected Spotify HTTP base");
        Method create = null;
        for (Method method : client.getClass().getMethods()) {
            if (method.getParameterCount() != 1 || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            try {
                if (!requestShape(method.getParameterTypes()[0], baseValue.getClass())) continue;
                if (create != null) throw new IOException("Ambiguous Spotify call factory");
                create = method;
            } catch (NoSuchMethodException ignored) { }
        }
        if (create == null) throw new IOException("Spotify call factory unavailable");
        Class<?> requestType = create.getParameterTypes()[0];
        Constructor<?> requestConstructor = null, builderConstructor = null;
        for (Constructor<?> constructor : requestType.getConstructors()) {
            if (constructor.getParameterCount() != 1) continue;
            try {
                Constructor<?> builder = builderConstructor(constructor.getParameterTypes()[0]);
                if (requestConstructor != null) throw new IOException("Ambiguous Spotify request builder");
                requestConstructor = constructor;
                builderConstructor = builder;
            } catch (NoSuchMethodException ignored) { }
        }
        Method buildRequest = null;
        if (requestConstructor == null) {
            for (Class<?> nested : requestType.getDeclaredClasses()) {
                try {
                    Constructor<?> constructor = nested.getConstructor();
                    Method method = uniqueMethod(nested, requestType);
                    if (buildRequest != null) throw new IOException("Ambiguous Spotify request builder");
                    builderConstructor = constructor;
                    buildRequest = method;
                } catch (NoSuchMethodException ignored) { }
            }
        }
        if (builderConstructor == null) throw new IOException("Spotify request builder unavailable");
        Class<?> builderType = builderConstructor.getDeclaringClass();
        String address = base.resolve(request == null ? path : request.path(epoch)).toASCIIString();
        Object builder = null;
        Method header = null;
        if (hooks != null && hooks.getDexKit() != null) {
            var setters = hooks.getDexKit().findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(builderType).paramTypes(String.class).usingEqStrings("ws:", "wss:")));
            if (setters.size() != 1) throw new IOException("Spotify URL setter fingerprint is ambiguous");
            Method setter = setters.get(0).getMethodInstance(hooks.getClassLoader());
            builder = newBuilder(builderConstructor);
            invoke(setter, builder, address);
            for (var candidate : hooks.getDexKit().findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(builderType).paramTypes(String.class, String.class)))) {
                boolean headerCall = false;
                for (var use : candidate.getUsingFields()) {
                    if (!use.getUsingType().toString().equals("Read") || !use.getField().getClassName().equals(builderType.getName())) continue;
                    Field field = use.getField().getFieldInstance(hooks.getClassLoader());
                    field.setAccessible(true);
                    Object value = field.get(builder);
                    if (value == null) continue;
                    for (var invoked : candidate.getInvokes()) {
                        if (invoked.getClassName().equals(value.getClass().getName()) && invoked.getParamCount() == 2 && invoked.getParamTypeNames().equals(java.util.List.of("java.lang.String", "java.lang.String"))) headerCall = true;
                    }
                }
                if (!headerCall) continue;
                Method method = candidate.getMethodInstance(hooks.getClassLoader());
                if (header == null || method.getName().compareTo(header.getName()) < 0) header = method;
            }
        } else {
            Field urlField = uniqueField(builderType, baseValue.getClass());
            for (Method method : builderType.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || !Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class})) continue;
                Object candidate = newBuilder(builderConstructor);
                try {
                    method.invoke(candidate, address);
                    Object value = urlField.get(candidate);
                    if (value == null || !address.equals(value.toString())) continue;
                    if (builder != null) throw new IOException("Ambiguous Spotify URL setter");
                    builder = candidate;
                } catch (InvocationTargetException ignored) { }
            }
            for (Method method : builderType.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) || !Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class, String.class})) continue;
                if (header == null || method.getName().compareTo(header.getName()) < 0) header = method;
            }
        }
        if (builder == null) throw new IOException("Spotify URL setter unavailable");
        if (header == null) throw new IOException("Spotify request header API unavailable");
        invoke(header, builder, "Accept", request == null ? "application/json" : "application/x-protobuf");
        Object requestValue = requestConstructor == null ? invoke(buildRequest, builder) : requestConstructor.newInstance(builder);
        Object destination = uniqueField(requestType, baseValue.getClass()).get(requestValue);
        if (destination == null || !address.equals(destination.toString())) throw new IOException("Spotify request URL validation failed");
        Object created = invoke(create, client, requestValue);
        if (created == null) throw new IOException("Unexpected Spotify call implementation");
        call = created;
        Method executeCall = null;
        if (hooks != null && hooks.getDexKit() != null) {
            for (var candidate : hooks.getDexKit().findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(call.getClass()).paramCount(0).usingEqStrings("Already Executed")))) {
                Method method = candidate.getMethodInstance(hooks.getClassLoader());
                if (!Closeable.class.isAssignableFrom(method.getReturnType())) continue;
                if (executeCall != null) throw new IOException("Ambiguous Spotify call execution API");
                executeCall = method;
            }
            if (executeCall == null) throw new IOException("Spotify call execution fingerprint unavailable");
        } else {
            for (Class<?> contract : call.getClass().getInterfaces()) {
                try {
                    Method method = uniqueMethod(contract, Closeable.class);
                    if (executeCall != null) throw new IOException("Ambiguous Spotify call execution API");
                    executeCall = method;
                } catch (NoSuchMethodException ignored) { }
            }
        }
        execute = executeCall == null ? uniqueMethod(call.getClass(), Closeable.class) : executeCall;
        cancel = call.getClass().getMethod("cancel");
        Class<?> responseType = execute.getReturnType();
        status = uniqueField(responseType, int.class);
        Field foundBody = null;
        for (Field field : responseType.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !Closeable.class.isAssignableFrom(field.getType()) || field.getType() == responseType) continue;
            try {
                uniqueMethod(field.getType(), InputStream.class);
                if (foundBody != null) throw new IOException("Ambiguous Spotify response body");
                foundBody = field;
            } catch (NoSuchMethodException ignored) { }
        }
        if (foundBody == null) throw new IOException("Spotify response body unavailable");
        body = foundBody;
        bodyLength = uniqueMethod(body.getType(), long.class);
        bodyStream = uniqueMethod(body.getType(), InputStream.class);
        try {
            Object timeout = null;
            Method configure = null;
            for (Field field : call.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    Method method = uniqueMethod(field.getType(), null, long.class, TimeUnit.class);
                    field.setAccessible(true);
                    Object value = field.get(call);
                    if (value == null) continue;
                    if (timeout != null) throw new IOException("Ambiguous Spotify call timeout");
                    timeout = value;
                    configure = method;
                } catch (NoSuchMethodException ignored) { }
            }
            if (timeout == null) throw new IOException("Spotify call timeout unavailable");
            invoke(configure, timeout, 20L, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | IOException error) {
            close();
            throw error;
        }
    }

    private static boolean requestShape(Class<?> type, Class<?> urlType) throws NoSuchMethodException {
        boolean method = false, url = urlType == null;
        for (Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() == String.class) method = true;
            if (field.getType() == urlType) url = true;
        }
        if (!method || !url) throw new NoSuchMethodException("Spotify request shape");
        return true;
    }

    private static Constructor<?> builderConstructor(Class<?> type) throws NoSuchMethodException {
        try {
            return type.getConstructor();
        } catch (NoSuchMethodException ignored) {
            return type.getConstructor(byte.class);
        }
    }

    private static Object newBuilder(Constructor<?> constructor) throws ReflectiveOperationException {
        return constructor.getParameterCount() == 0 ? constructor.newInstance() : constructor.newInstance((byte) 0);
    }

    private static Field uniqueField(Class<?> type, Class<?> valueType) throws NoSuchFieldException {
        Field result = null;
        for (Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType() != valueType) continue;
            if (result != null) throw new NoSuchFieldException("Ambiguous field in " + type.getName());
            field.setAccessible(true);
            result = field;
        }
        if (result == null) throw new NoSuchFieldException("Field in " + type.getName());
        return result;
    }

    private static Method uniqueMethod(Class<?> type, Class<?> resultType, Class<?>... parameters) throws NoSuchMethodException {
        Method result = null;
        for (Method method : type.getMethods()) {
            if (method.isBridge() || java.lang.reflect.Modifier.isStatic(method.getModifiers()) || !Arrays.equals(method.getParameterTypes(), parameters) || resultType != null && !resultType.isAssignableFrom(method.getReturnType())) continue;
            if (result != null) throw new NoSuchMethodException("Ambiguous method in " + type.getName());
            result = method;
        }
        if (result == null) throw new NoSuchMethodException("Method in " + type.getName());
        return result;
    }

    StorageResolution.Response executeOnWorker() throws IOException {
        if (request == null) throw new IOException("Not a resolver request");
        byte[] payload = readOnWorker();
        try {
            return request.parse(payload, currentEpoch.getAsLong());
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    byte[] metadataOnWorker() throws IOException {
        if (request != null) throw new IOException("Not a metadata request");
        return readOnWorker();
    }

    private byte[] readOnWorker() throws IOException {
        if (!started.compareAndSet(false, true)) throw new IOException("Resolver call already executed");
        requireCurrent();
        Object value = invoke(execute, call);
        if (!(value instanceof Closeable response)) throw new IOException("Unexpected resolver response");
        byte[] data = new byte[bodyLimit + 1];
        try (response) {
            requireCurrent();
            if (status.getInt(value) != 200) throw new IOException("Spotify resolver returned HTTP " + status.getInt(value));
            Object responseBody = body.get(value);
            if (responseBody == null) throw new IOException("Spotify resolver returned no body");
            long declared = (Long) invoke(bodyLength, responseBody);
            if (declared > bodyLimit) throw new IOException("Resolver response exceeds limit");
            Object streamValue = invoke(bodyStream, responseBody);
            if (!(streamValue instanceof InputStream stream)) throw new IOException("Resolver stream API unavailable");
            int used = 0;
            try (stream) {
                while (used < data.length) {
                    requireCurrent();
                    int count = stream.read(data, used, data.length - used);
                    if (count < 0) break;
                    if (count == 0) throw new IOException("Resolver stream made no progress");
                    used += count;
                }
            }
            if (used > bodyLimit) throw new IOException("Resolver response exceeds limit");
            requireCurrent();
            return Arrays.copyOf(data, used);
        } catch (IllegalAccessException error) {
            throw new IOException("Resolver response API unavailable", error);
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private void requireCurrent() throws IOException {
        if (closed.get() || currentEpoch.getAsLong() != epoch || Thread.currentThread().isInterrupted()) {
            close();
            throw new InterruptedIOException("Resolver request cancelled");
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static Object invoke(Method method, Object owner, Object... args) throws IOException {
        try {
            return method.invoke(owner, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof IOException failure) throw failure;
            throw new IOException("Spotify HTTP call failed", error.getCause());
        } catch (IllegalAccessException error) {
            throw new IOException("Spotify HTTP API unavailable", error);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            cancel.invoke(call);
        } catch (ReflectiveOperationException ignored) { }
    }
}
