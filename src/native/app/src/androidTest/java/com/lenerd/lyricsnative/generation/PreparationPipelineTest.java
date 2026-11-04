package com.lenerd.lyricsnative.generation;

import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lenerd.lyricsnative.sing.PreparationSpeedTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreparationPipelineTest {
    @Test
    public void fullSectionPreparation() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("preparation", "0").equals("1"));
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "preparation-speech.f32");
        var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("cat /data/local/tmp/lyrics-preparation-speech.f32");
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); var output = Files.newOutputStream(file.toPath())) {
            input.transferTo(output);
        }
        var bytes = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        assertTrue(bytes.remaining() > 80000);
        float[] speech = new float[bytes.remaining()];
        bytes.get(speech);
        int rate = 44100, frames = rate * 16;
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            double voice = speech[(int) ((long) i * 16000 / rate % speech.length)];
            double backing = 0.02 * Math.sin(i * 2 * Math.PI * 220 / rate) * Math.exp(-(i % 22050) / 3000.0);
            pcm[2 * i] = (short) Math.max(-32768, Math.min(32767, Math.round((voice + backing) * 32768)));
            pcm[2 * i + 1] = (short) Math.max(-32768, Math.min(32767, Math.round((voice - backing) * 32768)));
        }
        var decoded = new AudioCodec.Decoded(rate, frames);
        var chunk = new GenerationPlan.Chunk(List.of(new GenerationPlan.Line(0, 1500, 13500)), 0, 15000);
        try (var old = PreparationSpeedTest.separator(false, false); var fast = PreparationSpeedTest.separator(true, false); var held = PreparationSpeedTest.separator(true, true)) {
            assertTrue(old.backend().contains("NPU"));
            assertTrue(fast.backend().contains("NPU"));
            assertTrue(held.backend().contains("NPU"));
            float[] reference = null;
            for (int repeat = 0; repeat < 3; repeat++) {
                for (int pass = 0; pass < 3; pass++) {
                    int variant = (repeat + pass) % 3;
                    long start = System.nanoTime();
                    float[] vocals;
                    if (variant == 0) vocals = ReferencePreparation.prepare(ShortBuffer.wrap(pcm), decoded, chunk, old::separate, new OfflineSeparator.WindowCache(), () -> false).vocals();
                    else vocals = AudioPreparation.prepare(ShortBuffer.wrap(pcm), decoded, chunk, (variant == 1 ? fast : held)::separate, new OfflineSeparator.WindowCache(), () -> false).vocals();
                    double elapsed = (System.nanoTime() - start) / 1e6;
                    if (reference == null) reference = vocals;
                    assertEquals(240000, vocals.length);
                    double difference = 0, energy = 0, peak = 0;
                    for (int i = 0; i < vocals.length; i++) {
                        assertTrue(Float.isFinite(vocals[i]));
                        difference += Math.pow(vocals[i] - reference[i], 2);
                        energy += reference[i] * reference[i];
                        peak = Math.max(peak, Math.abs(vocals[i] - reference[i]));
                    }
                    double relative = Math.sqrt(difference / Math.max(energy, 1e-20));
                    assertTrue("Vocal preparation RMS difference=" + relative, relative < 0.01);
                    Log.i("PreparationBenchmark", "PIPELINE repeat=" + repeat + " variant=" + variant + " elapsed_ms=" + elapsed + " relative_error=" + relative + " peak_error=" + peak);
                }
            }
        }
    }
}
