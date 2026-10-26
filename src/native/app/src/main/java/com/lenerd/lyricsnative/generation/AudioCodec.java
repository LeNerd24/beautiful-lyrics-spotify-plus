package com.lenerd.lyricsnative.generation;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AudioCodec {
    private static final long TIMEOUT_US = 10_000;

    record Decoded(int rate, long frames) { }

    private AudioCodec() { }

    static Decoded decodeToPcm(File input, File output) throws IOException, InterruptedException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;

        try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(output), 1 << 16)) {
            extractor.setDataSource(input.getPath());

            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = candidate;
                    break;
                }
            }

            if (format == null) throw new IOException("Downloaded file has no audio track");

            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outputFormat = null;
            boolean inputDone = false;
            long frames = 0;
            byte[] scratch = new byte[1 << 16];

            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();

                while (!inputDone) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index < 0) break;

                    int size = extractor.readSampleData(decoder.getInputBuffer(index), 0);

                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }

                int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = decoder.getOutputFormat();
                } else if (index >= 0) {
                    if (outputFormat == null) outputFormat = decoder.getOutputFormat(index);

                    int channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    boolean floating = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) && outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    int sampleBytes = floating ? 4 : 2;

                    ByteBuffer buffer = decoder.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                    buffer.position(info.offset);

                    int count = info.size / (channels * sampleBytes);

                    Pcm16Writer.write(buffer, info.offset, count, channels, floating, stream, scratch);

                    frames += count;
                    decoder.releaseOutputBuffer(index, false);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }

            if (outputFormat == null || frames == 0) throw new IOException("Downloaded audio could not be decoded");
            return new Decoded(outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), frames);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException ignored) { }
                decoder.release();
            }

            extractor.release();
        }
    }

    static final class AacWriter implements AutoCloseable {
        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private final int rate;

        private int track = -1;
        private long frames;
        private boolean finished;

        AacWriter(File output, int rate) throws IOException {
            this.rate = rate;

            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);

            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                codec.start();
                muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException | RuntimeException error) {
                codec.release();
                throw error;
            }
        }

        void write(float[][] pcm, int count) {
            int written = 0;

            while (written < count) {
                int index = codec.dequeueInputBuffer(TIMEOUT_US);

                if (index < 0) {
                    drain(false);
                    continue;
                }

                ByteBuffer buffer = codec.getInputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                buffer.clear();

                int chunk = Math.min(count - written, buffer.remaining() / 4);

                for (int i = 0; i < chunk; i++) {
                    for (int c = 0; c < 2; c++) {
                        float value = pcm[c][written + i];
                        buffer.putShort((short) Math.max(-32768, Math.min(32767, Math.round((Float.isFinite(value) ? value : 0) * 32768))));
                    }
                }

                codec.queueInputBuffer(index, 0, chunk * 4, frames * 1_000_000L / rate, 0);
                frames += chunk;
                written += chunk;

                drain(false);
            }
        }

        void finish() {
            while (true) {
                int index = codec.dequeueInputBuffer(TIMEOUT_US);

                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
                }

                drain(false);
            }

            drain(true);
            muxer.stop();
            finished = true;
        }

        private void drain(boolean untilEnd) {
            while (true) {
                int index = codec.dequeueOutputBuffer(info, untilEnd ? TIMEOUT_US : 0);

                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (untilEnd) continue;
                    return;
                }

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    continue;
                }

                if (index < 0) continue;

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && track >= 0) muxer.writeSampleData(track, codec.getOutputBuffer(index), info);

                codec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }

        @Override
        public void close() {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) { }
            codec.release();

            if (!finished && track >= 0) {
                try {
                    muxer.stop();
                } catch (IllegalStateException ignored) { }
            }

            muxer.release();
        }
    }
}
