package com.lenerd.lyricsnative.generation;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class StorageResolution {
    private static final int MAX_BODY = 65_536, MAX_URLS = 32, MAX_URL_BYTES = 8192;

    private StorageResolution() { }

    static final class Request implements AutoCloseable {
        private final int selector;
        private final long epoch;
        private final byte[] fileId;
        private boolean closed;

        Request(int selector, byte[] fileId, long epoch) {
            if (fileId == null || fileId.length != 20 || (epoch & 1) == 0) throw new IllegalArgumentException("Invalid resource snapshot");
            this.selector = selector;
            this.epoch = epoch;
            this.fileId = fileId.clone();
        }

        synchronized String path(long currentEpoch) throws IOException {
            requireCurrent(currentEpoch);
            StringBuilder value = new StringBuilder("storage-resolve/v2/files/audio/interactive/").append(selector).append('/');
            for (byte item : fileId) value.append(Character.forDigit((item & 255) >>> 4, 16)).append(Character.forDigit(item & 15, 16));
            return value.append("?product=0").toString();
        }

        synchronized byte[] fileId(long currentEpoch) throws IOException {
            requireCurrent(currentEpoch);
            return fileId.clone();
        }

        synchronized Response parse(byte[] body, long currentEpoch) throws IOException {
            requireCurrent(currentEpoch);
            if (body == null || body.length > MAX_BODY) throw new IOException("Resolver response exceeds limit");
            Cursor input = new Cursor(body);
            ArrayList<URI> urls = new ArrayList<>();
            long result = 0;
            byte[] returnedId = null;
            try {
                while (input.remaining() > 0) {
                    long tag = input.varint();
                    if (tag <= 0 || tag > 0xffffffffL || tag >>> 3 == 0) throw new IOException("Invalid resolver field");
                    int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                    if (field == 1) {
                        input.requireWire(wire, 0);
                        result = input.varint();
                    } else if (field == 2) {
                        input.requireWire(wire, 2);
                        if (urls.size() >= MAX_URLS) throw new IOException("Too many resolver URLs");
                        int size = input.length();
                        if (size == 0 || size > MAX_URL_BYTES) throw new IOException("Invalid resolver URL length");
                        String value;
                        try {
                            value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body, input.position, size)).toString();
                        } catch (CharacterCodingException error) {
                            throw new IOException("Invalid resolver URL encoding");
                        }
                        input.skip(size);
                        urls.add(cdnUri(value));
                    } else if (field == 4) {
                        input.requireWire(wire, 2);
                        int size = input.length();
                        if (size != 0 && size != 20) throw new IOException("Invalid resolved file identifier");
                        if (returnedId != null) Arrays.fill(returnedId, (byte) 0);
                        returnedId = Arrays.copyOfRange(body, input.position, input.position + size);
                        input.skip(size);
                    } else input.skipField(wire);
                }
                if (result != 0) throw new IOException("Resource is not available from CDN");
                if (urls.isEmpty()) throw new IOException("Resolver returned no CDN URLs");
                boolean confirmed = returnedId != null && returnedId.length != 0;
                if (confirmed && !Arrays.equals(fileId, returnedId)) throw new IOException("Resolved resource does not match request");
                return new Response(urls, epoch, confirmed);
            } finally {
                if (returnedId != null) Arrays.fill(returnedId, (byte) 0);
            }
        }

        private void requireCurrent(long currentEpoch) throws IOException {
            if (closed || currentEpoch != epoch) throw new IOException("Resource request is no longer current");
        }

        @Override
        public synchronized void close() {
            closed = true;
            Arrays.fill(fileId, (byte) 0);
        }

        @Override
        public String toString() { return "StorageResolution.Request[redacted]"; }
    }

    static final class Response {
        private final List<URI> urls;
        private final long epoch;
        private final boolean fileIdConfirmed;

        private Response(List<URI> urls, long epoch, boolean fileIdConfirmed) {
            this.urls = List.copyOf(urls);
            this.epoch = epoch;
            this.fileIdConfirmed = fileIdConfirmed;
        }

        List<URI> urls(long currentEpoch) throws IOException {
            if (currentEpoch != epoch) throw new IOException("Resolved resource is no longer current");
            return urls;
        }

        boolean fileIdConfirmed() { return fileIdConfirmed; }

        @Override
        public String toString() { return "StorageResolution.Response[urls=" + urls.size() + ", fileIdConfirmed=" + fileIdConfirmed + "]"; }
    }

    private static URI cdnUri(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IOException("Unsupported CDN URL");
            return uri;
        } catch (URISyntaxException error) {
            throw new IOException("Malformed CDN URL");
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) { this.data = data; }
        int remaining() { return data.length - position; }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (remaining() == 0) throw new IOException("Truncated resolver varint");
                int item = data[position++] & 255;
                if (shift == 63 && (item & 254) != 0) throw new IOException("Resolver varint overflow");
                value |= (long) (item & 127) << shift;
                if ((item & 128) == 0) return value;
            }
            throw new IOException("Malformed resolver varint");
        }

        int length() throws IOException {
            long size = varint();
            if (size < 0 || size > remaining()) throw new IOException("Truncated resolver field");
            return (int) size;
        }

        void requireWire(int actual, int expected) throws IOException {
            if (actual != expected) throw new IOException("Unexpected resolver field encoding");
        }

        void skip(int size) throws IOException {
            if (size < 0 || size > remaining()) throw new IOException("Truncated resolver field");
            position += size;
        }

        void skipField(int wire) throws IOException {
            switch (wire) {
                case 0 -> varint();
                case 1 -> skip(8);
                case 2 -> skip(length());
                case 5 -> skip(4);
                default -> throw new IOException("Unsupported resolver field encoding");
            }
        }
    }
}
