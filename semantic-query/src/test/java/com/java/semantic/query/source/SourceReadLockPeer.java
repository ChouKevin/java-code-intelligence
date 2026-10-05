package com.java.semantic.query.source;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** JDK-only peer; ready means the OS lock is held, release ends this owned process. */
public final class SourceReadLockPeer {
    private SourceReadLockPeer() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 5 || !(args[0].equals("shared") || args[0].equals("exclusive"))) {
            throw new IllegalArgumentException("mode lock ready release deadlineMillis required");
        }
        boolean shared = args[0].equals("shared");
        Path ready = Path.of(args[2]);
        Path release = Path.of(args[3]);
        long deadline = System.nanoTime() + java.time.Duration.ofMillis(Long.parseLong(args[4])).toNanos();
        try (FileChannel channel = FileChannel.open(Path.of(args[1]), shared ? StandardOpenOption.READ : StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(ready.resolveSibling(ready.getFileName() + ".attempted"), "attempting");
            FileLock acquired = null;
            while (Objects.isNull(acquired) && System.nanoTime() - deadline < 0) {
                acquired = channel.tryLock(0, Long.MAX_VALUE, shared);
                if (Objects.isNull(acquired)) Thread.sleep(5);
            }
            if (Objects.isNull(acquired)) throw new IllegalStateException("lock deadline exceeded");
            try (FileLock lock = acquired) {
                if (lock.isShared() != shared) throw new IllegalStateException("unsupported locking mode");
                Files.writeString(ready, "locked");
                while (!Files.exists(release) && System.nanoTime() - deadline < 0) Thread.sleep(5);
                if (!Files.exists(release)) throw new IllegalStateException("release deadline exceeded");
            }
        }
    }
}
