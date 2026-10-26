package com.lenerd.lyricsnative.generation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class Pcm16Writer {
    private Pcm16Writer() { }

    static void write(ByteBuffer input, int offset, int frames, int channels, boolean floating, OutputStream output, byte[] scratch) throws IOException {
        ByteBuffer buffer = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(offset);
        buffer.limit(offset + frames * channels * (floating ? 4 : 2));

        if (channels == 2 && !floating) {
            while (buffer.hasRemaining()) {
                int count = Math.min(buffer.remaining(), scratch.length);
                buffer.get(scratch, 0, count);
                output.write(scratch, 0, count);
            }
            return;
        }

        int sampleBytes = floating ? 4 : 2;
        for (int position = 0; position < frames;) {
            int count = Math.min(frames - position, scratch.length / 4);
            for (int i = 0; i < count; i++) {
                int base = offset + (position + i) * channels * sampleBytes;
                short left = read(buffer, base, floating), right = channels > 1 ? read(buffer, base + sampleBytes, floating) : left;
                scratch[i * 4] = (byte) left;
                scratch[i * 4 + 1] = (byte) (left >> 8);
                scratch[i * 4 + 2] = (byte) right;
                scratch[i * 4 + 3] = (byte) (right >> 8);
            }
            output.write(scratch, 0, count * 4);
            position += count;
        }
    }

    private static short read(ByteBuffer buffer, int offset, boolean floating) {
        if (!floating) return buffer.getShort(offset);
        float value = buffer.getFloat(offset);
        return (short) Math.max(-32768, Math.min(32767, Math.round((Float.isFinite(value) ? value : 0) * 32768)));
    }
}
