package com.lenerd.lyricsnative.generation;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class SpotifyAudioRuntimeLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SpotifyAudioRuntimeLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static SpotifyAudioRuntimeLock acquire(Path path, long deadline) throws IOException, InterruptedException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean acquired = false;
        try {
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                try {
                    FileLock lock = channel.tryLock();
                    if (lock != null) {
                        acquired = true;
                        return new SpotifyAudioRuntimeLock(channel, lock);
                    }
                } catch (OverlappingFileLockException ignored) { }
                Thread.sleep(50);
            }
            throw new IOException("Another Spotify audio runtime is still shutting down");
        } finally {
            if (!acquired) channel.close();
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
        } finally {
            try {
                channel.close();
            } catch (IOException ignored) { }
        }
    }
}
