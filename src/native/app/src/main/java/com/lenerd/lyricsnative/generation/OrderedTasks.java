package com.lenerd.lyricsnative.generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class OrderedTasks<R> implements AutoCloseable {
    interface Publish<R> {
        void accept(R result) throws Exception;
    }

    private final ExecutorService workers;
    private final Semaphore capacity;
    private final Publish<R> publish;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Map<Integer, R> completed = new HashMap<>();
    private final List<Future<?>> tasks = new ArrayList<>();
    private int submitted, published;
    private volatile boolean closed;

    OrderedTasks(int parallelism, Publish<R> publish) {
        workers = Executors.newFixedThreadPool(parallelism, runnable -> new Thread(runnable, "LyricsLineAlignment"));
        capacity = new Semaphore(parallelism);
        this.publish = publish;
    }

    void submit(Callable<R> compute) throws Exception {
        checkFailure();
        while (!capacity.tryAcquire(100, TimeUnit.MILLISECONDS)) checkFailure();
        try {
            checkFailure();
            int index = submitted++;
            tasks.add(workers.submit(() -> {
                try {
                    R result = compute.call();
                    synchronized (completed) {
                        checkFailure();
                        completed.put(index, result);
                        while (completed.containsKey(published)) {
                            publish.accept(completed.remove(published++));
                            capacity.release();
                        }
                    }
                } catch (Exception | Error error) {
                    failure.compareAndSet(null, error);
                    throw error;
                }
                return null;
            }));
        } catch (Exception | Error error) {
            capacity.release();
            throw error;
        }
    }

    void finish() throws Exception {
        for (Future<?> task : tasks) {
            checkFailure();
            ChunkPipeline.await(task);
        }
        checkFailure();
    }

    private void checkFailure() throws Exception {
        Throwable error = failure.get();
        if (error instanceof Exception exception) throw exception;
        if (error instanceof Error fatal) throw fatal;
        if (closed) throw new IllegalStateException("Line alignment workers are closed");
    }

    @Override
    public void close() {
        closed = true;
        for (Future<?> task : tasks) task.cancel(true);
        workers.shutdownNow();
        boolean interrupted = Thread.interrupted();
        while (!workers.isTerminated()) {
            try {
                workers.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
