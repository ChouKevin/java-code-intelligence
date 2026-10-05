package com.java.semantic.indexer.source;

import com.java.semantic.indexer.source.SourceRetentionStore.DeleteIntent;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Idle maintenance on the existing dispatcher, never a second worker or job queue. */
@Component
public final class SourceGarbageCollector {
    private static final Logger LOG = LoggerFactory.getLogger(SourceGarbageCollector.class);
    private final RepositoryProperties properties;
    private final RepositoryRegistry registry;
    private final SourcePublicationStore publications;
    private final SourceRetentionStore retention;
    private final Clock clock;
    private final Path published;
    private Instant nextRun = Instant.MIN;
    private boolean firstRun = true;
    private static final class Counts { long deleted; long skipped; long failed; long current; long young; }
    private record Deleted(long files, long bytes) {}

    public SourceGarbageCollector(RepositoryProperties properties, RepositoryRegistry registry,
            SourcePublicationStore publications, SourceRetentionStore retention, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.publications = publications;
        this.retention = retention;
        this.clock = clock;
        published = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
    }

    public void runIfDue() {
        if (!properties.getSourceRetention().isEnabled() || clock.instant().isBefore(nextRun)) return;
        String run = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Counts counts = new Counts();
        LOG.info("event=source_gc_start gcRunId={} trigger={} retentionSeconds={}", run,
                firstRun ? "startup" : "scheduled", SourceRetentionStore.RETENTION.toSeconds());
        firstRun = false;
        try {
            for (SourceRepositoryDescriptor descriptor : registry.descriptors()) {
                if (Thread.currentThread().isInterrupted() || !properties.getSourceRetention().isEnabled()) break;
                scan(new RepositoryId(descriptor.repositoryId()), run, counts);
            }
        } catch (RuntimeException exception) {
            counts.failed++;
            failure(run, "none", "none", "scan", exception);
        } finally {
            nextRun = clock.instant().plus(properties.getSourceRetention().getInterval());
            String result = counts.failed == 0 ? "success" : counts.deleted > 0 || counts.skipped > 0 ? "partial" : "failed";
            LOG.info("event=source_gc_end gcRunId={} result={} deleted={} skipped={} failed={} current={} underRetention={} durationMs={}",
                    run, result, counts.deleted, counts.skipped, counts.failed, counts.current, counts.young, elapsed(started));
        }
    }

    private void scan(RepositoryId id, String run, Counts counts) {
        try {
            registry.require(id);
            retention.requireLock(id);
            Optional<DeleteIntent> pending = retention.pendingDeletion(id);
            if (pending.isPresent()) {
                DeleteIntent intent = pending.orElseThrow();
                if (!collect(id, intent, true, counts)) return;
            }
            SourceRepositoryState state = publications.state(id);
            Map<String, Instant> retired = retention.reconcile(id, state, clock.instant());
            for (PreparedRevision receipt : state.published().values()) {
                if (Thread.currentThread().isInterrupted() || !properties.getSourceRetention().isEnabled()) return;
                String sha = receipt.context().revision();
                if (state.current().filter(current -> current.revision().equals(sha)).isPresent()) { counts.current++; continue; }
                Instant at = retired.get(sha);
                if (Objects.isNull(at) || clock.instant().isBefore(at.plus(SourceRetentionStore.RETENTION))) { counts.young++; continue; }
                DeleteIntent intent = new DeleteIntent(1, id.value(), sha, registry.origin(id), receipt.manifestDigest(), run);
                if (!collect(id, intent, false, counts)) return;
            }
        } catch (IOException | RuntimeException exception) {
            counts.failed++;
            failure(run, id.value(), "none", "metadata", exception);
        }
    }

    /** Return false when the repo is busy or its pending intent must survive until a later scan. */
    private boolean collect(RepositoryId id, DeleteIntent intent, boolean recovery, Counts counts) throws IOException {
        long started = System.nanoTime();
        String stage = "admission_lock";
        try {
            if (!properties.getSourceRetention().isEnabled()) return false;
            registry.requireOrigin(id, intent.originFingerprint());
            retention.requireLock(id);
            Path lockPath = published.resolve(id.value()).resolve(SourceRepositoryState.READ_LOCK_FILE_NAME);
            boolean withdrawn = false;
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock acquired;
                try { acquired = channel.tryLock(); }
                catch (OverlappingFileLockException exception) { acquired = null; }
                if (Objects.isNull(acquired)) {
                    counts.skipped++;
                    LOG.info("event=source_gc_skip gcRunId={} repositoryId={} reason=READ_IN_PROGRESS", intent.gcRunId(), id.value());
                    return false;
                }
                try (FileLock guard = acquired) {
                    if (guard.isShared()) throw new IOException("exclusive locking unsupported");
                    stage = "revalidate";
                    SourceRepositoryState latest = publications.state(id);
                    Map<String, Instant> retirement = retention.reconcile(id, latest, clock.instant());
                    if (latest.current().filter(current -> current.revision().equals(intent.revision())).isPresent()) {
                        if (recovery) retention.cancelDeletion(intent);
                        counts.skipped++;
                        recovered(intent, recovery, "cancelled_current");
                        return true;
                    }
                    PreparedRevision receipt = latest.published().get(intent.revision());
                    if (Objects.nonNull(receipt)) {
                        if (!receipt.manifestDigest().equals(intent.manifestDigest())) throw new IOException("deletion receipt changed");
                        Instant at = retirement.get(intent.revision());
                        if (Objects.isNull(at) || clock.instant().isBefore(at.plus(SourceRetentionStore.RETENTION))) {
                            if (recovery) retention.cancelDeletion(intent);
                            counts.young++;
                            recovered(intent, recovery, "cancelled_not_due");
                            return true;
                        }
                        // Validate before persisting a new intent, and revalidate again at withdrawal.
                        publications.manifest(receipt.context(), intent.manifestDigest());
                        stage = "intent";
                        retention.beginDeletion(intent);
                        stage = "withdrawal";
                        withdrawn = publications.withdraw(intent, clock.instant());
                        if (!withdrawn) {
                            retention.cancelDeletion(intent);
                            counts.skipped++;
                            recovered(intent, recovery, "cancelled_not_due");
                            return true;
                        }
                    } else {
                        if (!recovery || retention.pendingDeletion(id).filter(intent::equals).isEmpty())
                            throw new IOException("unmarked tree is not reclaimable");
                        withdrawn = true;
                    }
                }
            }
            // Withdrawal is durable. Other published revisions may be read throughout physical deletion.
            if (!withdrawn || !properties.getSourceRetention().isEnabled()) return false;
            stage = "physical_delete";
            Deleted deleted = deleteTree(id, intent);
            stage = "finish";
            retention.finishDeletion(intent);
            counts.deleted++;
            LOG.info("event=source_gc_deleted gcRunId={} repositoryId={} revision={} result=deleted recovery={} deletedFileCount={} deletedLogicalBytes={} durationMs={}",
                    intent.gcRunId(), id.value(), intent.revision(), recovery, deleted.files(), deleted.bytes(), elapsed(started));
            recovered(intent, recovery, "completed");
            return true;
        } catch (IOException | RuntimeException exception) {
            counts.failed++;
            failure(intent.gcRunId(), id.value(), intent.revision(), stage, exception);
            // A pre-intent validation failure is isolated; a durable intent fences the repo until recovery.
            try { return retention.pendingDeletion(id).isEmpty(); }
            catch (RuntimeException unavailable) { return false; }
        }
    }

    private Deleted deleteTree(RepositoryId id, DeleteIntent intent) throws IOException {
        registry.requireOrigin(id, intent.originFingerprint());
        Path parent = published.resolve(id.value()).resolve("revisions");
        Path tree = parent.resolve(intent.revision());
        requireDirectories(parent);
        long[] totals = {0, 0};
        if (Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
            requireDirectories(tree);
            Path manifest = tree.resolve("manifest.json");
            if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                        || !DurableSourceFiles.sha256(DurableSourceFiles.boundedRead(manifest, 64 * 1024)).equals(intent.manifestDigest()))
                    throw new IOException("pending deletion manifest changed");
            }
            // Preflight the surviving tree without following links; no unsafe leaf is silently unlinked.
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) throw new IOException("unsafe deletion entry");
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("collection interrupted");
                    if (!attrs.isRegularFile()) throw new IOException("unsafe deletion entry");
                    long bytes = attrs.size();
                    Files.delete(path);
                    totals[0]++; totals[1] += bytes;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    if (Objects.nonNull(failure)) throw failure;
                    DurableSourceFiles.forceDirectory(path);
                    Files.delete(path);
                    DurableSourceFiles.forceDirectory(path.getParent());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        DurableSourceFiles.forceDirectory(parent);
        return new Deleted(totals[0], totals[1]);
    }

    private void requireDirectories(Path leaf) throws IOException {
        Path path = published;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe published root");
        for (Path segment : published.relativize(leaf)) {
            path = path.resolve(segment);
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe deletion parent");
        }
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static void recovered(DeleteIntent intent, boolean recovery, String result) {
        if (recovery) LOG.info("event=source_gc_recovery gcRunId={} repositoryId={} revision={} result={}",
                intent.gcRunId(), intent.repositoryId(), intent.revision(), result);
    }
    private static void failure(String run, String repository, String sha, String stage, Exception exception) {
        LOG.error("event=source_gc_failure gcRunId={} repositoryId={} revision={} stage={} errorCode=GC_OPERATION_FAILED exceptionType={}",
                run, repository, sha, stage, exception.getClass().getSimpleName());
    }
}
