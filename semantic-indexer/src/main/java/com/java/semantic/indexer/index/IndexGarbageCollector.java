package com.java.semantic.indexer.index;

import com.java.semantic.indexer.index.IndexRetentionStore.Intent;
import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.indexer.source.SourceRetentionStore;
import com.java.semantic.indexer.source.SourceRetentionStore.DeleteIntent;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.index.IndexRepositoryState.RevisionIndexes;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Existing idle dispatcher and source read guard protect every index withdrawal. */
public final class IndexGarbageCollector {
    private final RepositoryProperties properties;
    private final RepositoryRegistry registry;
    private final SourcePublicationStore sources;
    private final SourceRetentionStore sourceRetention;
    private final IndexPublicationStore publications;
    private final IndexRetentionStore retention;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Path sourceRoot;
    private final Path indexRoot;
    private Instant nextRun = Instant.MIN;

    public IndexGarbageCollector(RepositoryProperties properties, RepositoryRegistry registry, SourcePublicationStore sources,
            SourceRetentionStore sourceRetention, IndexPublicationStore publications, IndexRetentionStore retention,
            ObjectMapper mapper, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.sources = sources;
        this.sourceRetention = sourceRetention;
        this.publications = publications;
        this.retention = retention;
        this.mapper = mapper;
        this.clock = clock;
        sourceRoot = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
        indexRoot = Path.of(properties.getIndexPublishedRoot()).toAbsolutePath().normalize();
    }

    public boolean isDue() { return enabled() && !clock.instant().isBefore(nextRun); }

    public void runIfDue() {
        if (!isDue()) return;
        try {
            for (SourceRepositoryDescriptor descriptor : registry.descriptors()) {
                if (!enabled()) return;
                RepositoryId repository = new RepositoryId(descriptor.repositoryId());
                try {
                    exclusive(repository, () -> collectObsolete(repository));
                } catch (IOException | RuntimeException failure) {
                    LoggerFactory.getLogger(IndexGarbageCollector.class).warn("event=index_gc_failed repositoryId={}", repository.value());
                }
            }
        } finally {
            nextRun = clock.instant().plus(properties.getSourceRetention().getInterval());
        }
    }

    /** Source collector already holds the exclusive guard; persist before its source withdrawal. */
    public void beginSource(DeleteIntent source) {
        requireEnabled();
        RepositoryId repository = new RepositoryId(source.repositoryId());
        registry.requireOrigin(repository, source.originFingerprint());
        if (sourceRetention.pendingDeletion(repository).filter(source::equals).isEmpty())
            throw new IllegalStateException("source deletion fence is absent");
        Optional<Intent> pending = retention.pending(repository);
        if (pending.isPresent()) {
            Intent intent = pending.orElseThrow();
            if (intent.sourceDeletion()) {
                requireCompanion(intent, source);
                return;
            }
            complete(intent);
        }
        RevisionIndexes revision = publications.state(repository).revisions().get(source.revision());
        if (Objects.nonNull(revision) && !revision.sourceManifestDigest().equals(source.manifestDigest()))
            throw new IllegalStateException("companion source identity mismatch");
        List<Receipt> targets = Objects.isNull(revision) ? List.of() : List.copyOf(revision.generations().values());
        for (Receipt target : targets) validateArtifact(target, false);
        retention.begin(new Intent(1, source.repositoryId(), source.revision(), source.originFingerprint(),
                source.manifestDigest(), source.gcRunId(), true, targets));
    }

    /** Source bytes must already be absent; original source intent remains until this succeeds. */
    public boolean finishSource(DeleteIntent source) throws IOException {
        if (!enabled()) return false;
        RepositoryId repository = new RepositoryId(source.repositoryId());
        return exclusive(repository, () -> {
            if (sourceRetention.pendingDeletion(repository).filter(source::equals).isEmpty())
                throw new IllegalStateException("source deletion fence changed");
            Path sourceTree = sourceRoot.resolve(source.repositoryId()).resolve("revisions").resolve(source.revision());
            IndexFiles.safe(sourceTree);
            if (Files.exists(sourceTree, LinkOption.NOFOLLOW_LINKS)) throw new IOException("source physical deletion is unfinished");
            // After a crash past companion completion, membership is already empty; no orphan scan is performed.
            beginSource(source);
            Intent intent = retention.pending(repository).orElseThrow();
            requireCompanion(intent, source);
            complete(intent);
        });
    }

    public void cancelSource(DeleteIntent source) {
        RepositoryId repository = new RepositoryId(source.repositoryId());
        Optional<Intent> pending = retention.pending(repository);
        if (pending.isEmpty() || !pending.orElseThrow().sourceDeletion()) return;
        Intent intent = pending.orElseThrow();
        requireCompanion(intent, source);
        PreparedRevision receipt = sources.state(repository).published().get(source.revision());
        if (Objects.isNull(receipt) || !receipt.manifestDigest().equals(source.manifestDigest()))
            throw new IllegalStateException("withdrawn source deletion cannot be cancelled");
        retention.finish(intent);
    }

    private void collectObsolete(RepositoryId repository) {
        Optional<Intent> pending = retention.pending(repository);
        if (pending.isPresent()) {
            if (pending.orElseThrow().sourceDeletion()) return; // Only source GC can prove the physical-source handoff.
            complete(pending.orElseThrow());
        }
        for (RevisionIndexes revision : publications.state(repository).revisions().values()) {
            for (Receipt receipt : revision.generations().values()) {
                if (!enabled()) return;
                String generation = receipt.manifest().indexGeneration();
                if (revision.selectedGeneration().filter(generation::equals).isPresent()) continue;
                PreparedRevision source = sources.state(repository).published().get(receipt.manifest().context().revision());
                if (Objects.isNull(source) || !source.manifestDigest().equals(receipt.manifest().sourceManifestDigest())) continue;
                validateArtifact(receipt, false);
                IndexManifest manifest = receipt.manifest();
                Intent intent = new Intent(1, repository.value(), manifest.context().revision(), manifest.originFingerprint(),
                        manifest.sourceManifestDigest(), UUID.randomUUID().toString(), false, List.of(receipt));
                retention.begin(intent);
                complete(intent);
            }
        }
    }

    private void complete(Intent intent) {
        requireEnabled();
        registry.requireOrigin(new RepositoryId(intent.repositoryId()), intent.originFingerprint());
        if (retention.pending(new RepositoryId(intent.repositoryId())).filter(intent::equals).isEmpty())
            throw new IllegalStateException("index deletion intent changed");
        for (Receipt target : intent.targets()) validateArtifact(target, true);
        for (Receipt target : intent.targets()) {
            requireEnabled();
            publications.withdraw(target, intent.sourceDeletion());
            deleteArtifact(target);
        }
        if (intent.sourceDeletion()) {
            RevisionIndexes remaining = publications.state(new RepositoryId(intent.repositoryId())).revisions().get(intent.revision());
            if (Objects.nonNull(remaining)) throw new IllegalStateException("unrecorded companion membership remains");
            removeEmptyRevisionDirectory(intent);
        }
        retention.finish(intent);
    }

    private void removeEmptyRevisionDirectory(Intent intent) {
        Path directory = indexRoot.resolve(intent.repositoryId()).resolve("revisions").resolve(intent.revision());
        try {
            IndexFiles.safe(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe index revision directory");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                if (entries.iterator().hasNext()) return; // Unrecorded artifacts are not adopted for cleanup.
            }
            requireEnabled();
            Files.delete(directory);
            DurableSourceFiles.forceDirectory(directory.getParent());
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    private void validateArtifact(Receipt receipt, boolean recovering) {
        Path directory = artifact(receipt);
        try {
            IndexFiles.safe(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (recovering) return;
                throw new IOException("published index generation is absent");
            }
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid index generation path");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path file : entries) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || !file.getFileName().toString().equals("manifest.json") && !file.getFileName().toString().equals("index.sqlite"))
                        throw new IOException("unsafe index deletion entry");
                }
            }
            Path manifest = directory.resolve("manifest.json");
            if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = IndexFiles.read(manifest, IndexFiles.MAX_MANIFEST);
                if (!DurableSourceFiles.sha256(bytes).equals(receipt.manifestDigest())
                        || !mapper.readValue(bytes, IndexManifest.class).equals(receipt.manifest()))
                    throw new IOException("index deletion manifest changed");
            } else if (!recovering) throw new IOException("index deletion manifest absent");
            Path database = directory.resolve("index.sqlite");
            if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(database) != receipt.manifest().databaseBytes()
                        || !IndexFiles.digest(database).equals(receipt.manifest().databaseDigest()))
                    throw new IOException("index deletion database changed");
            } else if (!recovering) throw new IOException("index deletion database absent");
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    private void deleteArtifact(Receipt receipt) {
        Path directory = artifact(receipt);
        try {
            validateArtifact(receipt, true);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
            requireEnabled();
            Files.deleteIfExists(directory.resolve("index.sqlite"));
            DurableSourceFiles.forceDirectory(directory);
            requireEnabled();
            Files.deleteIfExists(directory.resolve("manifest.json"));
            DurableSourceFiles.forceDirectory(directory);
            requireEnabled();
            Files.delete(directory);
            DurableSourceFiles.forceDirectory(directory.getParent());
        } catch (IOException failure) { throw IndexFiles.unavailable(failure); }
    }

    private Path artifact(Receipt receipt) {
        IndexManifest manifest = receipt.manifest();
        return indexRoot.resolve(manifest.context().repositoryId()).resolve("revisions")
                .resolve(manifest.context().revision()).resolve(manifest.indexGeneration());
    }
    private boolean enabled() { return properties.getSourceRetention().isEnabled() && !Thread.currentThread().isInterrupted(); }
    private void requireEnabled() { if (!enabled()) throw new IllegalStateException("index collection disabled or interrupted"); }
    private static void requireCompanion(Intent intent, DeleteIntent source) {
        if (!intent.sourceDeletion() || !intent.repositoryId().equals(source.repositoryId()) || !intent.revision().equals(source.revision())
                || !intent.originFingerprint().equals(source.originFingerprint()) || !intent.sourceManifestDigest().equals(source.manifestDigest())
                || !intent.gcRunId().equals(source.gcRunId())) throw new IllegalStateException("source companion intent mismatch");
    }
    @FunctionalInterface private interface Guarded { void run() throws IOException; }
    private boolean exclusive(RepositoryId repository, Guarded operation) throws IOException {
        if (!enabled()) return false;
        sourceRetention.requireLock(repository);
        Path path = sourceRoot.resolve(repository.value()).resolve(SourceRepositoryState.READ_LOCK_FILE_NAME);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { return false; }
            if (Objects.isNull(acquired)) return false;
            try (FileLock guard = acquired) {
                if (guard.isShared()) throw new IOException("exclusive locking unsupported");
                operation.run();
                return true;
            }
        }
    }
}
