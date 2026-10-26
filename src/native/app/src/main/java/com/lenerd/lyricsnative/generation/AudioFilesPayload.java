package com.lenerd.lyricsnative.generation;

import java.io.IOException;
import java.util.Arrays;

final class AudioFilesPayload {
    static final class Selection implements AutoCloseable {
        private final byte[] id;
        final int format;
        private boolean closed;
        Selection(byte[] id, int format) {
            this.id = id;
            this.format = format;
        }
        byte[] fileId() throws IOException {
            if (closed) throw new IOException("File selection closed");
            return id.clone();
        }
        @Override
        public void close() {
            closed = true;
            Arrays.fill(id, (byte) 0);
        }
    }

    static Selection select(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > 262144) throw new IOException("Invalid audio file payload");
        Cursor input = new Cursor(payload, 0, payload.length);
        Selection selected = null;
        boolean completed = false;
        int count = 0;
        try {
            while (input.position < input.end) {
                long tag = input.tag();
                if (tag >>> 3 != 1) {
                    input.skip(tag);
                    continue;
                }
                if (++count > 64 || (tag & 7) != 2) throw new IOException("Invalid audio file list");
                Cursor extended = input.message();
                while (extended.position < extended.end) {
                    long extendedTag = extended.tag();
                    if (extendedTag >>> 3 != 1) {
                        extended.skip(extendedTag);
                        continue;
                    }
                    if ((extendedTag & 7) != 2) throw new IOException("Invalid extended audio file");
                    Cursor file = extended.message();
                    byte[] id = null;
                    long format = 0;
                    try {
                        while (file.position < file.end) {
                            long field = file.tag();
                            if (field >>> 3 == 1) {
                                if ((field & 7) != 2 || id != null) throw new IOException("Invalid audio file identifier");
                                Cursor bytes = file.message();
                                if (bytes.end - bytes.position != 20) throw new IOException("Invalid audio file identifier length");
                                id = Arrays.copyOfRange(payload, bytes.position, bytes.end);
                            } else if (field >>> 3 == 2) {
                                if ((field & 7) != 0) throw new IOException("Invalid audio format");
                                format = file.varint();
                            } else file.skip(field);
                        }
                        if (id != null && format >= 0 && format <= 2 && (selected == null || selected.format != 0 && format == 0)) {
                            if (selected != null) selected.close();
                            selected = new Selection(id, (int) format);
                            id = null;
                        }
                    } finally {
                        if (id != null) Arrays.fill(id, (byte) 0);
                    }
                }
            }
            if (selected == null) throw new IOException("No supported full audio file");
            completed = true;
            return selected;
        } finally {
            if (!completed && selected != null) selected.close();
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private final int end;
        private int position;
        Cursor(byte[] data, int position, int end) {
            this.data = data;
            this.position = position;
            this.end = end;
        }
        long tag() throws IOException {
            long tag = varint();
            if (tag <= 0 || tag > 0xffffffffL || tag >>> 3 == 0) throw new IOException("Invalid audio metadata field");
            return tag;
        }
        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (position >= end) throw new IOException("Truncated audio metadata");
                int item = data[position++] & 255;
                if (shift == 63 && (item & 254) != 0) throw new IOException("Audio metadata overflow");
                result |= (long) (item & 127) << shift;
                if ((item & 128) == 0) return result;
            }
            throw new IOException("Invalid audio metadata varint");
        }
        Cursor message() throws IOException {
            long size = varint();
            if (size < 0 || size > end - position) throw new IOException("Truncated audio metadata message");
            Cursor value = new Cursor(data, position, position + (int) size);
            position = value.end;
            return value;
        }
        void skip(long tag) throws IOException {
            switch ((int) (tag & 7)) {
                case 0 -> varint();
                case 2 -> message();
                case 1, 5 -> {
                    int size = (tag & 7) == 1 ? 8 : 4;
                    if (end - position < size) throw new IOException("Truncated audio metadata field");
                    position += size;
                }
                default -> throw new IOException("Invalid audio metadata wire type");
            }
        }
    }

    private AudioFilesPayload() { }
}
