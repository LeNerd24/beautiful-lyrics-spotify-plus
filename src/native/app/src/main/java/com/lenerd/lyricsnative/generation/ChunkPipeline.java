package com.lenerd.lyricsnative.generation;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ChunkPipeline {
    interface Prepare<T, R> {
        R apply(T input) throws Exception;
    }
    interface Consume<T, R> {
        void accept(T input, R prepared) throws Exception;
    }
    private record Ready<T, R>(T input, R value) { }

    private ChunkPipeline() { }

    static <T, R> void run(List<T> inputs, Prepare<T, R> prepare, Consume<T, R> consume, ExecutorService executor) throws Exception {
        ArrayBlockingQueue<Ready<T, R>> ready = new ArrayBlockingQueue<>(1);
        Future<?> producer = executor.submit(() -> {
            for (T input : inputs) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                ready.put(new Ready<>(input, prepare.apply(input)));
            }
            return null;
        });

        try {
            for (int consumed = 0; consumed < inputs.size();) {
                Ready<T, R> chunk = ready.poll(100, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    consume.accept(chunk.input, chunk.value);
                    consumed++;
                } else if (producer.isDone()) {
                    await(producer);
                    if (ready.isEmpty()) throw new IllegalStateException("Audio preparation ended before all sections were ready");
                }
            }
            await(producer);
        } finally {
            producer.cancel(true);
            executor.shutdownNow();
            boolean interrupted = Thread.interrupted();
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(error.getCause());
        }
    }
}
