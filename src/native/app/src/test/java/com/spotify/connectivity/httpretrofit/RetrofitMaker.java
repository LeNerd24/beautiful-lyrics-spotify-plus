package com.spotify.connectivity.httpretrofit;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public final class RetrofitMaker {
    private final Retrofit mRetrofitWebgate = new Retrofit();
    public final Client observedClient = mRetrofitWebgate.client;

    public static final class Retrofit {
        public final Client client = new Client();
        public final Address address = new Address("https://spclient.wg.spotify.com/");
    }

    public static final class Address {
        final String value;
        Address(String value) { this.value = value; }
        @Override
        public String toString() { return value; }
    }

    public static final class Builder {
        public Address destination;
        public String accept;
        public void remove(String value) { }
        public void destination(String value) { destination = new Address(value); }
        public void header(String name, String value) { accept = value; }
    }

    public static final class Request {
        public final Builder builder;
        public final Address destination;
        public final String verb = "GET";
        public Request(Builder builder) { this.builder = builder; destination = builder.destination; }
    }

    public static final class Client {
        public Call lastCall;
        public Call create(Request request) { return lastCall = new Call(request); }
    }

    public static final class Timeout {
        public long seconds;
        public void duration(long duration, TimeUnit unit) { seconds = unit.toSeconds(duration); }
    }

    public static final class Call {
        public final Request request;
        public final Timeout deadline = new Timeout();
        public boolean cancelled;
        Call(Request request) { this.request = request; }
        public Response perform() { return new Response(); }
        public void cancel() { cancelled = true; }
    }

    public static final class Response implements Closeable {
        public final int code = 200;
        public final Body content = new Body();
        @Override
        public void close() { }
    }

    public static final class Body implements Closeable {
        public long length() { return 2; }
        public InputStream stream() { return new ByteArrayInputStream(new byte[] {123, 125}); }
        @Override
        public void close() { }
    }
}
