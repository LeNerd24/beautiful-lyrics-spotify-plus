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
        this(maker, request, currentEpoch, null, 65_536);
    }

    static SpotifyResolverCall metadata(Object maker, String trackId, LongSupplier currentEpoch) throws ReflectiveOperationException, IOException { return new SpotifyResolverCall(maker, null, currentEpoch, trackPath(trackId), 262_144); }

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

    private SpotifyResolverCall(Object maker, StorageResolution.Request request, LongSupplier currentEpoch, String path, int bodyLimit) throws ReflectiveOperationException, IOException {
        this.request = request;
        this.currentEpoch = currentEpoch;
        this.bodyLimit = bodyLimit;
        epoch = currentEpoch.getAsLong();
        if ((epoch & 1) == 0) throw new InterruptedIOException("Resolver session is no longer eligible");
        if (maker == null || !maker.getClass().getName().equals("com.spotify.connectivity.httpretrofit.RetrofitMaker")) throw new IOException("Spotify HTTP client unavailable");
        Object retrofit = field(maker, "mRetrofitWebgate"), client = field(retrofit, "b");
        if (!retrofit.getClass().getName().equals("p.oow0") || !client.getClass().getName().equals("p.slk0")) throw new IOException("Spotify HTTP client version mismatch");
        URI base;
        try {
            base = URI.create(field(retrofit, "c").toString());
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid Spotify HTTP base");
        }
        if (!"https".equals(base.getScheme()) || !"spclient.wg.spotify.com".equals(base.getHost()) || !"/".equals(base.getPath()) || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null || (base.getPort() != -1 && base.getPort() != 443)) throw new IOException("Unexpected Spotify HTTP base");
        ClassLoader loader = maker.getClass().getClassLoader();
        Class<?> builderType = Class.forName("p.l6w0", false, loader), requestType = Class.forName("p.n6w0", false, loader), callType = Class.forName("p.e8u0", false, loader), responseType = Class.forName("p.zew0", false, loader), bodyType = Class.forName("p.bgw0", false, loader);
        if (!Closeable.class.isAssignableFrom(responseType)) throw new IOException("Response closing API unavailable");
        Constructor<?> builderConstructor = builderType.getConstructor(), requestConstructor = requestType.getConstructor(builderType);
        Method url = builderType.getMethod("g", String.class), create = client.getClass().getMethod("b", requestType);
        execute = callType.getMethod("g");
        cancel = callType.getMethod("cancel");
        status = responseType.getField("d");
        body = responseType.getField("g");
        bodyLength = bodyType.getMethod("e");
        bodyStream = bodyType.getMethod("a");
        Object builder = builderConstructor.newInstance();
        invoke(url, builder, base.resolve(request == null ? path : request.path(epoch)).toASCIIString());
        invoke(builderType.getMethod("c", String.class, String.class), builder, "Accept", request == null ? "application/json" : "application/x-protobuf");
        Object created = invoke(create, client, requestConstructor.newInstance(builder));
        if (created == null || created.getClass() != callType) throw new IOException("Unexpected Spotify call implementation");
        call = created;
        try {
            Object timeout = field(call, "f");
            invoke(timeout.getClass().getMethod("g", long.class, TimeUnit.class), timeout, 20L, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException | IOException error) {
            close();
            throw error;
        }
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
