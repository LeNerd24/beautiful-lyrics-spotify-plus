package com.lenerd.lyricsnative.sing;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class OboeSingEngine implements AutoCloseable {
    private final Supplier<VocalSeparator> separator;
    private final Consumer<String> status;
    private final BiConsumer<String, Throwable> failure;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> new Thread(task, "SpotifySing-oboe"));
    private final float[] input = new float[16384], output = new float[16384];
    private final long[] metadata = new long[2];
    private final byte[] bytes = new byte[16384 * 4];
    private final java.nio.FloatBuffer floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    private volatile boolean ready, closed, active;
    private RealtimeBuffer buffer;
    private long generation = -1, windows, lastLog;

    OboeSingEngine(Supplier<VocalSeparator> separator, Consumer<String> status, BiConsumer<String, Throwable> failure) {
        this.separator = separator;
        this.status = status;
        this.failure = failure;
    }

    synchronized void install() {
        if (closed) throw new IllegalStateException("Oboe Sing is closed");
        ready = OboeSingNative.install();
        if (!ready) throw new IllegalStateException("Unsupported Oboe callback layout");
        try { worker.scheduleWithFixedDelay(this::process, 0, 5, TimeUnit.MILLISECONDS); }
        catch (RuntimeException error) {
            OboeSingNative.close();
            ready = false;
            throw error;
        }
        Log.i("SpotifySing", "OBOE_HOOK_READY native callback; inference worker; fixed playback delay");
    }

    boolean ready() { return ready && !closed; }
    long delayMs() { return active ? 1200 : 0; }

    void enable(boolean value, float gain) {
        if (!ready()) return;
        active = value;
        OboeSingNative.gain(gain);
        OboeSingNative.enable(value);
    }

    void gain(float value) { if (ready()) OboeSingNative.gain(value); }
    void reset() { if (ready()) OboeSingNative.reset(); }

    private void process() {
        if (closed || !active || separator.get() == null) return;
        try {
            if (buffer != null && buffer.ended() && OboeSingNative.state()[0] == generation) return;
            int frames;
            while (!closed && active && (frames = OboeSingNative.read(input, metadata)) > 0) {
                long[] state = OboeSingNative.state();
                if (state.length < 7 || state[0] != metadata[0]) continue;
                if (generation != metadata[0]) {
                    generation = metadata[0];
                    buffer = new RealtimeBuffer((int) state[1], 4);
                    buffer.setGain(0);
                    Log.i("SpotifySing", "OBOE_PCM_BOUND rate=" + state[1] + " encoding=" + state[2] + " delay_ms=1200 generation=" + generation);
                }
                if (metadata[1] != buffer.captured()) throw new IllegalStateException("Oboe input queue overrun");
                if (state.length > 9 && state[9] >= 0) frames = (int) Math.max(0, Math.min(frames, state[9] - metadata[1]));
                floats.position(0);
                floats.put(input, 0, frames * 2);
                if (buffer.capture(bytes, frames * 8, frames * 8) != frames * 8) throw new IllegalStateException("Oboe model input queue full");
                if (state.length > 9 && state[9] >= 0 && buffer.captured() >= state[9]) buffer.end();
                RealtimeBuffer.Window window;
                while (!closed && active && (window = buffer.takeWindow()) != null) {
                    VocalSeparator model = separator.get();
                    if (model == null) return;
                    long started = System.nanoTime();
                    float[][] vocals;
                    synchronized (model) { vocals = model.separate(window.pcm()); }
                    buffer.publish(window, vocals);
                    windows++;
                    double milliseconds = (System.nanoTime() - started) / 1e6;
                    status.accept(String.format(java.util.Locale.ROOT, "Live · Oboe · %.0f ms/window", milliseconds));
                }
                while (!closed && active) {
                    RealtimeBuffer.Output result = buffer.output(output.length / 2, false);
                    if (result.bytes().length == 0) break;
                    int count = result.bytes().length / 8;
                    ByteBuffer.wrap(result.bytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(output, 0, count * 2);
                    OboeSingNative.write(output, count, generation, result.offset());
                    buffer.commit(result, result.bytes().length);
                }
                if (System.nanoTime() - lastLog > 5_000_000_000L) {
                    lastLog = System.nanoTime();
                    state = OboeSingNative.state();
                    Log.i("SpotifySing", "OBOE_LIVE windows=" + windows + " captured=" + state[3] + " delayed=" + state[4] + " separated_frames=" + state[5] + " fallback_frames=" + state[6]);
                }
                if (buffer.ended()) break;
            }
        } catch (Throwable error) {
            failure.accept("Oboe processing failed · original audio", error);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        active = false;
        worker.shutdownNow();
        if (ready) OboeSingNative.close();
        ready = false;
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
