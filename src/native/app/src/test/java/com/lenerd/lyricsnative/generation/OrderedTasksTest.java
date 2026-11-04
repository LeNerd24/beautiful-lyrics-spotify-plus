package com.lenerd.lyricsnative.generation;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OrderedTasksTest {
    @Test
    public void computesConcurrentlyAndPublishesInOrder() throws Exception {
        var secondFinished = new CountDownLatch(1);
        var published = new ArrayList<Integer>();
        try (var tasks = new OrderedTasks<Integer>(2, published::add)) {
            tasks.submit(() -> {
                assertTrue(secondFinished.await(2, TimeUnit.SECONDS));
                assertTrue(published.isEmpty());
                return 0;
            });
            tasks.submit(() -> {
                secondFinished.countDown();
                return 1;
            });
            tasks.submit(() -> 2);
            tasks.finish();
        }
        assertEquals(List.of(0, 1, 2), published);
    }

    @Test
    public void publishesTheFirstResultWithoutWaitingForMoreInputs() throws Exception {
        var visible = new CountDownLatch(1);
        try (var tasks = new OrderedTasks<Integer>(2, result -> visible.countDown())) {
            tasks.submit(() -> 1);
            assertTrue(visible.await(2, TimeUnit.SECONDS));
            tasks.finish();
        }
    }

    @Test
    public void boundsOutstandingWorkUntilPublication() throws Exception {
        var gate = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var third = new CountDownLatch(1);
        var runner = Executors.newSingleThreadExecutor();
        try (var tasks = new OrderedTasks<Integer>(2, result -> { })) {
            tasks.submit(() -> { gate.await(); return 0; });
            tasks.submit(() -> { second.countDown(); return 1; });
            assertTrue(second.await(2, TimeUnit.SECONDS));
            var producer = runner.submit(() -> { tasks.submit(() -> { third.countDown(); return 2; }); return null; });
            assertFalse(third.await(100, TimeUnit.MILLISECONDS));
            gate.countDown();
            producer.get(2, TimeUnit.SECONDS);
            tasks.finish();
            assertEquals(0, third.getCount());
        } finally {
            gate.countDown();
            runner.shutdownNow();
        }
    }

    @Test
    public void computationAndPublicationFailuresPropagate() throws Exception {
        try (var tasks = new OrderedTasks<Integer>(2, result -> { })) {
            tasks.submit(() -> { throw new IOException("compute"); });
            assertEquals("compute", assertThrows(IOException.class, tasks::finish).getMessage());
        }
        try (var tasks = new OrderedTasks<Integer>(2, result -> { throw new IOException("publish"); })) {
            tasks.submit(() -> 1);
            assertEquals("publish", assertThrows(IOException.class, tasks::finish).getMessage());
        }
    }

    @Test
    public void failureUnblocksTheProducerAndCloseJoinsWorkers() throws Exception {
        var started = new CountDownLatch(1);
        var fail = new CountDownLatch(1);
        var running = new AtomicInteger();
        try (var tasks = new OrderedTasks<Integer>(2, result -> { })) {
            tasks.submit(() -> {
                fail.await();
                throw new IOException("stop");
            });
            tasks.submit(() -> {
                running.incrementAndGet();
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                    return 1;
                } finally {
                    running.decrementAndGet();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            fail.countDown();
            assertThrows(IOException.class, () -> tasks.submit(() -> 2));
        }
        assertEquals(0, running.get());
    }
}
