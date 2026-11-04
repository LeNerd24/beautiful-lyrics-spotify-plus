package com.lenerd.lyricsnative.generation;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DecodingLatencyTest {
    @Test
    public void measuresThreeMinuteDecode() throws Exception {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("preparation", "0").equals("1"));
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File source = new File(directory, "decode-latency.m4a"), output = new File(directory, "decode-latency.pcm");
        int rate = 44100, frames = rate * 180;
        float[][] block = new float[2][4096];
        for (int c = 0; c < 2; c++) for (int i = 0; i < block[c].length; i++) block[c][i] = (float) (0.1 * Math.sin(i * (c == 0 ? 0.03 : 0.04)));
        try {
            try (var writer = new AudioCodec.AacWriter(source, rate)) {
                for (int at = 0; at < frames; at += block[0].length) writer.write(block, Math.min(block[0].length, frames - at));
                writer.finish();
            }
            byte[] expected = null;
            for (int run = 0; run < 3; run++) {
                long started = System.nanoTime();
                var decoded = AudioCodec.decodeToPcm(source, output);
                double elapsed = (System.nanoTime() - started) / 1e6;
                assertEquals(rate, decoded.rate());
                assertTrue(Math.abs(decoded.frames() - frames) < 4096);
                assertEquals(decoded.frames() * 4, output.length());
                var digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(output.toPath())) {
                    byte[] bytes = new byte[65536];
                    for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
                }
                byte[] actual = digest.digest();
                if (expected == null) expected = actual;
                assertArrayEquals(expected, actual);
                Log.i("PreparationBenchmark", "DECODE run=" + run + " duration_s=180 elapsed_ms=" + elapsed + " pcm_exact=true");
            }
        } finally {
            Files.deleteIfExists(source.toPath());
            Files.deleteIfExists(output.toPath());
        }
    }
}
