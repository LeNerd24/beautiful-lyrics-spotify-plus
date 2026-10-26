package com.lenerd.lyricsnative.generation;

import java.io.IOException;

final class AudioContainer {
    private AudioContainer() { }

    static int start(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 12) throw new IOException("Audio container is truncated");
        for (int offset = 0; offset < Math.min(4096, bytes.length - 11); offset++) {
            if (matches(bytes, offset, "OggS") && offset + 27 <= bytes.length && bytes[offset + 4] == 0 && (bytes[offset + 5] & 255) == 2) {
                int segments = bytes[offset + 26] & 255, payload = offset + 27 + segments;
                if (segments == 0 || payload > bytes.length) continue;
                int pageBytes = 0;
                for (int segment = 0; segment < segments; segment++) pageBytes += bytes[offset + 27 + segment] & 255;
                if (pageBytes < 7 || payload + pageBytes > bytes.length) continue;
                if ((bytes[payload] == 1 && matches(bytes, payload + 1, "vorbis")) || matches(bytes, payload, "OpusHead")) return offset;
            }
            if (matches(bytes, offset, "fLaC") && offset + 42 <= bytes.length && (bytes[offset + 4] & 127) == 0 && bytes[offset + 5] == 0 && bytes[offset + 6] == 0 && (bytes[offset + 7] & 255) == 34) return offset;
            if (offset == 0 && (matches(bytes, 0, "ID3") || matches(bytes, 4, "ftyp"))) return 0;
        }
        throw new IOException("Decrypted audio container is not recognized");
    }

    private static boolean matches(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int index = 0; index < value.length(); index++)
            if ((bytes[offset + index] & 255) != value.charAt(index)) return false;
        return true;
    }
}
