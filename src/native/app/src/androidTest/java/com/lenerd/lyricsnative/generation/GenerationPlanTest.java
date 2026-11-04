package com.lenerd.lyricsnative.generation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class GenerationPlanTest {
    @Test
    public void smallerSectionsRetainEveryLineAndContext() {
        List<GenerationPlan.Line> input = new ArrayList<>();
        for (int i = 0; i < 30; i++) input.add(new GenerationPlan.Line(i, 2000 + i * 2400, 3800 + i * 2400));
        var plan = GenerationPlan.create(input, 27000, 80000);
        List<Integer> order = new ArrayList<>();
        for (var section : plan) {
            var first = section.lines().get(0);
            var last = section.lines().get(section.lines().size() - 1);
            assertTrue(last.end() - first.start() <= 6000);
            assertEquals(Math.max(0, first.start() - 1500), section.start(), 0);
            assertEquals(Math.min(80000, last.end() + 1500), section.end(), 0);
            for (var line : section.lines()) order.add(line.index());
        }
        assertEquals(30, order.size());
        assertEquals(30, new java.util.HashSet<>(order).size());
        assertEquals(Integer.valueOf(10), order.get(0));
        assertEquals(Integer.valueOf(9), order.get(order.size() - 1));
        for (int i = 0; i < 8; i++) assertEquals(1, plan.get(i).lines().size());
    }

    @Test
    public void longLinesAndRecordingEdgesRemainIntact() {
        var lines = List.of(new GenerationPlan.Line(0, 0, 100), new GenerationPlan.Line(1, 200, 9000));
        var plan = GenerationPlan.create(lines, 0, 10000);
        assertEquals(2, plan.size());
        assertEquals(0, plan.get(0).start(), 0);
        assertEquals(10000, plan.get(1).end(), 0);
        assertEquals(lines.get(1), plan.get(1).lines().get(0));
    }
}
