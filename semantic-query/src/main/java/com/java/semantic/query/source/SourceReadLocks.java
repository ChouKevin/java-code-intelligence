package com.java.semantic.query.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.SourceRepositoryState;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** One manager per application; a repo's waiting/active readers share exactly one channel. */
public final class SourceReadLocks {
    private final Path root;
    private final Map<RepositoryId, Entry> entries = new HashMap<>();
    private static final class Entry {
        final FileChannel channel;
        FileLock lock;
        int references;
        Entry(FileChannel channel) { this.channel = channel; }
    }
    public SourceReadLocks(Path publishedRoot) { root = publishedRoot.toAbsolutePath().normalize(); }

    public Lease acquire(RepositoryId id, long deadlineNanos) {
        Entry entry;
        synchronized (this) {
            LocalSourceRevisionCatalog.check(deadlineNanos);
            entry = entries.get(id);
            if (Objects.isNull(entry)) {
                Path path = root.resolve(id.value()).resolve(SourceRepositoryState.READ_LOCK_FILE_NAME);
                LocalSourceRevisionCatalog.noLinks(path);
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
                try {
                    entry = new Entry(FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    entries.put(id, entry);
                } catch (IOException exception) { throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, exception); }
            }
            entry.references++;
        }
        boolean transferred = false;
        try {
            while (true) {
                LocalSourceRevisionCatalog.check(deadlineNanos);
                synchronized (this) {
                    if (Objects.isNull(entry.lock)) {
                        entry.lock = entry.channel.tryLock(0, Long.MAX_VALUE, true);
                        if (Objects.nonNull(entry.lock) && !entry.lock.isShared()) {
                            throw new IOException("shared locking unsupported");
                        }
                    }
                    if (Objects.nonNull(entry.lock)) {
                        Lease lease = new Lease(this, id, entry);
                        transferred = true;
                        return lease;
                    }
                }
                Thread.sleep(5);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT, exception);
        } catch (IOException | OverlappingFileLockException exception) {
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, exception);
        } finally {
            if (!transferred) release(id, entry);
        }
    }

    private synchronized void release(RepositoryId id, Entry entry) {
        if (--entry.references > 0) return;
        entries.remove(id, entry);
        try {
            // Close only the final reader's channel. POSIX may drop every process lock on this inode on close.
            entry.channel.close();
        } catch (IOException exception) { throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, exception); }
    }

    public static final class Lease implements AutoCloseable {
        private final SourceReadLocks owner;
        private final RepositoryId id;
        private final Entry entry;
        private boolean closed;
        private Lease(SourceReadLocks owner, RepositoryId id, Entry entry) { this.owner = owner; this.id = id; this.entry = entry; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.release(id, entry);
        }
    }
}
