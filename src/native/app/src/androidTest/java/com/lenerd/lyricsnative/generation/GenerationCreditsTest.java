package com.lenerd.lyricsnative.generation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GenerationCreditsTest {
    private static List<SyllableLyrics.Line> lines() {
        var word = new LineAligner.Word("Hello", 1000, 1500);
        word.syllables = List.of(new LineAligner.Syllable("Hello", 1000, 1500));
        return List.of(new SyllableLyrics.Line(false, SyllableLyrics.tokenize("Hello"), List.of(word)));
    }

    @Test
    public void savedGeneratedLyricsRetainWriters() throws Exception {
        JSONArray writers = new JSONArray().put("Writer One").put("Writer Two");
        JSONObject lyrics = new JSONObject(SyllableLyrics.build(lines(), writers).toString());
        assertEquals("Syllable", lyrics.getString("Type"));
        assertEquals(writers.toString(), lyrics.getJSONArray("SongWriters").toString());
        assertEquals("Hello", lyrics.getJSONArray("Content").getJSONObject(0).getJSONObject("Lead").getJSONArray("Syllables").getJSONObject(0).getString("Text"));
        writers.put("Another Writer");
        assertEquals(2, lyrics.getJSONArray("SongWriters").length());
    }

    @Test
    public void missingAndEmptyWritersRemainValid() throws Exception {
        assertFalse(SyllableLyrics.build(lines()).has("SongWriters"));
        assertEquals(0, SyllableLyrics.build(lines(), new JSONArray()).getJSONArray("SongWriters").length());
    }
}
