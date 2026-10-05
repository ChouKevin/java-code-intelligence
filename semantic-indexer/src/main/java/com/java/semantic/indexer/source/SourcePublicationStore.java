package com.java.semantic.indexer.source;

import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.SourcePreparationJob;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.indexer.source.SourceRetentionStore.DeleteIntent;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRepositoryState.CurrentPublication;
import com.java.semantic.model.source.SourceRepositoryState.PreparationPhase;
import com.java.semantic.model.source.SourceRepositoryState.PreparationStatus;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Immutable revisions and one atomic, bounded membership/current state replacement. */
@Component
public final class SourcePublicationStore {
    private static final Logger LOG = LoggerFactory.getLogger(SourcePublicationStore.class);
    private static final long MAX_STATE = 4L * 1024 * 1024;
    private static final long MAX_MANIFEST = 64 * 1024;
    private static final int MAX_FRAME = 64 * 1024;
    private final Path admin;
    private final Path published;
    private final ObjectMapper mapper;
    private final FileSourceJobStore jobs;
    private final RepositoryRegistry registry;

    private final SourceRetentionStore retention;
    public SourcePublicationStore(RepositoryProperties properties, ObjectMapper mapper,
            DurableSourceFiles ownership, FileSourceJobStore jobs, SourceRetentionStore retention) {
        this.admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        this.published = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.jobs = jobs;
        this.registry = new RepositoryRegistry(properties);
        this.retention = retention;
    }

    public synchronized void initializeLifecycle(RepositoryId id) {
        SourceRepositoryState state = state(id);
        for (PreparedRevision receipt : state.published().values()) {
            validateRevision(revisionPath(receipt.context()), receipt.context(), receipt.manifestDigest());
        }
        retention.initialize(id, state);
    }

    /** Caller holds the exclusive read guard; serialize against preparation status writes. */
    public synchronized boolean withdraw(DeleteIntent intent, Instant now) {
        RepositoryId id = new RepositoryId(intent.repositoryId());
        registry.requireOrigin(id, intent.originFingerprint());
        if (retention.pendingDeletion(id).filter(intent::equals).isEmpty()) {
            throw new IllegalStateException("deletion intent changed");
        }
        SourceRepositoryState latest = state(id);
        if (latest.current().filter(current -> current.revision().equals(intent.revision())).isPresent()) return false;
        PreparedRevision receipt = latest.published().get(intent.revision());
        if (Objects.isNull(receipt)) return false;
        if (!receipt.manifestDigest().equals(intent.manifestDigest())) throw new IllegalStateException("deletion digest mismatch");
        Instant retired = retention.reconcile(id, latest, now).get(intent.revision());
        if (Objects.isNull(retired) || now.isBefore(retired.plus(SourceRetentionStore.RETENTION))) return false;
        validateRevision(revisionPath(receipt.context()), receipt.context(), receipt.manifestDigest());
        Map<String, PreparedRevision> membership = new HashMap<>(latest.published());
        membership.remove(intent.revision());
        replace(new SourceRepositoryState(latest.formatVersion(), id.value(), latest.current(), membership, latest.preparation()));
        return true;
    }

    public synchronized SourceRepositoryState state(RepositoryId id) {
        registry.require(id);
        Path file = published.resolve(id.value()).resolve("state.json");
        if (!Files.exists(file)) {
            return new SourceRepositoryState(SourceRevisionManifest.FORMAT_VERSION, id.value(), Optional.empty(),
                    Map.of(), new PreparationStatus(PreparationPhase.IDLE, Optional.empty(), Optional.empty()));
        }
        try {
            SourceRepositoryState state = mapper.readValue(DurableSourceFiles.boundedRead(file, MAX_STATE), SourceRepositoryState.class);
            if (!state.repositoryId().equals(id.value())) {
                throw new IOException("state repository identity mismatch");
            }
            return state;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("source state unavailable", exception);
        }
    }

    public void updatePreparation(SourcePreparationJob job) {
        registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
        // Claim/admission is durable before its public status. Serialize their observation with
        // job writes so a delayed ACCEPTED update cannot rewind RUNNING or a later admission.
        synchronized (jobs) {
            SourcePreparationJob persisted = jobs.find(new RepositoryId(job.repositoryId()),
                    new PreparationRequestId(job.requestId()))
                    .filter(stored -> stored.jobId().equals(job.jobId()))
                    .orElseThrow(() -> new IllegalStateException("source job identity mismatch"));
            if (persisted.phase() != job.phase()) {
                jobs.statusPublished(job);
                return;
            }
            synchronized (this) {
                RepositoryId id = new RepositoryId(job.repositoryId());
                SourceRepositoryState old = state(id);
                PreparationPhase phase = PreparationPhase.valueOf(job.phase().name());
                if (old.preparation().jobId().isPresent()) {
                    if (!old.preparation().jobId().orElseThrow().equals(job.jobId())) {
                        if (job.phase() != SourcePreparationJob.Phase.ACCEPTED) {
                            jobs.statusPublished(job);
                            return;
                        }
                    } else if (phase.ordinal() <= old.preparation().phase().ordinal()) {
                        jobs.statusPublished(job);
                        return;
                    }
                }
                replace(new SourceRepositoryState(old.formatVersion(), id.value(), old.current(), old.published(),
                        new PreparationStatus(phase, Optional.of(job.jobId()), job.failureCode())));
            }
            jobs.statusPublished(job);
        }
    }

    public synchronized Path seal(SourcePreparationJob job, Path staging, SourceRevisionManifest manifest) {
        registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
        requireJobContext(job, manifest);
        retention.beforePublication(new RepositoryId(job.repositoryId()), RepositoryRevision.ofSha(manifest.context().revision()));
        Path finalPath = revisionPath(manifest.context());
        if (Files.exists(finalPath)) {
            PreparedRevision existing = state(new RepositoryId(job.repositoryId())).published().get(manifest.context().revision());
            if (Objects.isNull(existing)) {
                throw new IllegalStateException("unpublished revision is not adoptable");
            }
            validateRevision(finalPath, manifest.context(), existing.manifestDigest());
            return finalPath;
        }
        try {
            Path expectedStage = Path.of(staging.toString()).toAbsolutePath().normalize();
            if (!expectedStage.equals(admin.resolve("staging").resolve(job.jobId()))) {
                throw new IOException("staging directory does not match the preparation job");
            }
            if (Files.isSymbolicLink(expectedStage) || !Files.isDirectory(expectedStage, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("invalid staging directory");
            }
            byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
            DurableSourceFiles.ensureDirectories(admin, expectedStage, DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.atomicBytes(expectedStage.resolve("manifest.json"), manifestBytes, MAX_MANIFEST,
                    DurableSourceFiles.Visibility.PRIVATE);
            validateRevision(expectedStage, manifest.context(), DurableSourceFiles.sha256(manifestBytes));
            DurableSourceFiles.preparePublishedTree(expectedStage);
            DurableSourceFiles.forceTree(expectedStage);
            DurableSourceFiles.ensureDirectories(published, finalPath.getParent(),
                    DurableSourceFiles.Visibility.PUBLISHED);
            Files.move(expectedStage, finalPath, StandardCopyOption.ATOMIC_MOVE);
            DurableSourceFiles.forceDirectory(finalPath.getParent());
            return finalPath;
        } catch (IOException exception) {
            throw new IllegalStateException("source revision could not be durably sealed", exception);
        }
    }

    public synchronized PreparedRevision publish(SourcePreparationJob job, SourceRevisionManifest manifest) {
        registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
        requireJobContext(job, manifest);
        RepositoryId repository = new RepositoryId(job.repositoryId());
        SourceRepositoryState previous = state(repository);
        if (!previous.current().equals(job.expectedCurrent())) {
            throw new IllegalStateException("current publication changed since acceptance");
        }
        Path path = revisionPath(manifest.context());
        PreparedRevision existing = previous.published().get(manifest.context().revision());
        String manifestDigest = validateRevision(path, manifest.context(),
                Objects.isNull(existing) ? Optional.empty() : Optional.of(existing.manifestDigest()));
        if (!mapper.readValue(readManifest(path), SourceRevisionManifest.class).equals(manifest)) {
            throw new IllegalStateException("sealed manifest differs from requested publication");
        }
        PreparedRevision receipt = Objects.nonNull(existing) ? existing : new PreparedRevision(manifest.context(),
                manifestDigest, job.jobId(), manifest.preparedAt());
        Map<String, PreparedRevision> membership = new HashMap<>(previous.published());
        membership.put(manifest.context().revision(), receipt);
        CurrentPublication current = new CurrentPublication(manifest.context().revision(), manifestDigest,
                job.jobId(), Instant.now());
        retention.beforePublication(repository, RepositoryRevision.ofSha(current.revision()));
        replace(new SourceRepositoryState(SourceRevisionManifest.FORMAT_VERSION, repository.value(), Optional.of(current),
                membership, new PreparationStatus(PreparationPhase.COMPLETE, Optional.of(job.jobId()), Optional.empty())));
        try {
            retention.afterPublication(repository, previous.current(), current);
        } catch (RuntimeException exception) {
            // Publication already committed. A missing retirement entry earns a fresh window on reconciliation.
            LOG.error("event=source_retirement_record_failed repositoryId={} revision={} stage=post_publication errorCode=RETIREMENT_WRITE_FAILED exceptionType={}",
                    repository.value(), current.revision(), exception.getClass().getSimpleName());
        }
        return receipt;
    }

    public synchronized Optional<PreparedRevision> lookupPublishedJob(SourcePreparationJob job) {
        registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
        if (job.phase() != SourcePreparationJob.Phase.RUNNING || job.resolvedRevision().isEmpty()) {
            return Optional.empty();
        }
        try {
            SourceRepositoryState current = state(new RepositoryId(job.repositoryId()));
            CurrentPublication pointer = current.current().orElseThrow();
            if (!pointer.publicationJobId().equals(job.jobId())
                    || !pointer.revision().equals(job.resolvedRevision().orElseThrow())) {
                return Optional.empty();
            }
            PreparedRevision receipt = current.published().get(pointer.revision());
            if (Objects.isNull(receipt) || !receipt.manifestDigest().equals(pointer.manifestDigest())) {
                return Optional.empty();
            }
            validateRevision(revisionPath(receipt.context()), receipt.context(), Optional.of(receipt.manifestDigest()));
            return Optional.of(receipt);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public void requireJobOrigin(SourcePreparationJob job) {
        registry.requireOrigin(new RepositoryId(job.repositoryId()), job.originFingerprint());
    }

    public SourceRevisionManifest manifest(SourceContext context, String digest) {
        Path path = revisionPath(context);
        validateRevision(path, context, Optional.of(digest));
        return mapper.readValue(readManifest(path), SourceRevisionManifest.class);
    }

    private byte[] readManifest(Path path) {
        try {
            return DurableSourceFiles.boundedRead(path.resolve("manifest.json"), MAX_MANIFEST);
        } catch (IOException exception) {
            throw new IllegalStateException("sealed source manifest unavailable", exception);
        }
    }

    private String validateRevision(Path path, SourceContext expected, String digest) {
        return validateRevision(path, expected, Optional.of(digest));
    }

    private String validateRevision(Path path, SourceContext expected, Optional<String> expectedDigest) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("sealed revision missing");
            }
            byte[] bytes = readManifest(path);
            String digest = DurableSourceFiles.sha256(bytes);
            if (expectedDigest.isPresent() && !expectedDigest.orElseThrow().equals(digest)) {
                throw new IOException("manifest digest mismatch");
            }
            SourceRevisionManifest manifest = mapper.readValue(bytes, SourceRevisionManifest.class);
            if (!manifest.context().equals(expected) || manifest.formatVersion() != SourceRevisionManifest.FORMAT_VERSION
                    || manifest.policyVersion() != SourceRevisionManifest.POLICY_VERSION) {
                throw new IOException("manifest identity or policy mismatch");
            }
            Path inventory = path.resolve("inventory.jsonl");
            if (!DurableSourceFiles.sha256(inventory).equals(manifest.inventoryDigest())) {
                throw new IOException("inventory digest mismatch");
            }
            long readable = 0;
            Map<EntryStatus, Long> unsupported = new java.util.EnumMap<>(EntryStatus.class);
            byte[] preceding = new byte[0];
            try (InputStream input = new java.io.BufferedInputStream(Files.newInputStream(inventory))) {
                java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
                int next;
                while ((next = input.read()) >= 0) {
                    if (next != '\n') {
                        if (frame.size() >= MAX_FRAME - 1) {
                            throw new IOException("inventory frame too large");
                        }
                        frame.write(next);
                        continue;
                    }
                    SourceInventoryEntry entry = mapper.readValue(frame.toByteArray(), SourceInventoryEntry.class);
                    String key = entry.kind() == EntryKind.DIRECTORY ? entry.path() + "/" : entry.path();
                    byte[] ordered = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (preceding.length != 0 && java.util.Arrays.compareUnsigned(preceding, ordered) >= 0) {
                        throw new IOException("inventory is not ordered by Git tree keys");
                    }
                    preceding = ordered;
                    if (com.java.semantic.model.source.SourcePathPolicy.isExcluded(entry.path())) {
                        throw new IOException("excluded source appears in inventory");
                    }
                    frame.reset();
                    if (entry.kind() == EntryKind.DIRECTORY) {
                        if (!Files.isDirectory(path.resolve("tree").resolve(entry.path()), LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("inventory directory is absent");
                        }
                        continue;
                    }
                    if (entry.status().orElseThrow() == EntryStatus.TEXT) {
                        Path file = path.resolve("tree").resolve(entry.path());
                        if (!file.normalize().startsWith(path.resolve("tree")) || Files.isSymbolicLink(file)
                                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                                || Files.size(file) != entry.byteLength()
                                || !DurableSourceFiles.sha256(file).equals(entry.contentDigest().orElseThrow())) {
                            throw new IOException("published blob does not match inventory");
                        }
                        readable++;
                    }
                    else {
                        EntryStatus status = entry.status().orElseThrow();
                        unsupported.merge(status, 1L, Long::sum);
                        if (Files.exists(path.resolve("tree").resolve(entry.path()), LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("unsupported source has readable tree content");
                        }
                    }
                }
                if (frame.size() != 0) {
                    throw new IOException("inventory frame is not LF terminated");
                }
            }
            long listedUnsupportedPaths = unsupported.getOrDefault(EntryStatus.UNSUPPORTED_PATH, 0L);
            unsupported.remove(EntryStatus.UNSUPPORTED_PATH);
            Map<EntryStatus, Long> covered = new java.util.EnumMap<>(EntryStatus.class);
            covered.putAll(manifest.coverage().unsupportedCounts());
            long coveredUnsupportedPaths = covered.getOrDefault(EntryStatus.UNSUPPORTED_PATH, 0L);
            covered.remove(EntryStatus.UNSUPPORTED_PATH);
            if (readable != manifest.coverage().readableFileCount() || !unsupported.equals(covered)
                    || listedUnsupportedPaths > coveredUnsupportedPaths) {
                throw new IOException("inventory coverage mismatch");
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(path.resolve("tree"))) {
                if (paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).count() != readable) {
                    throw new IOException("unindexed file in published tree");
                }
            }
            return digest;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("sealed source revision unavailable", exception);
        }
    }

    private static void requireJobContext(SourcePreparationJob job, SourceRevisionManifest manifest) {
        if (job.phase() != SourcePreparationJob.Phase.RUNNING || job.resolvedRevision().isEmpty()
                || !manifest.context().repositoryId().equals(job.repositoryId())
                || !manifest.context().revision().equals(job.resolvedRevision().orElseThrow())) {
            throw new IllegalStateException("publication requires a running pinned job");
        }
    }

    private Path revisionPath(SourceContext context) {
        return published.resolve(context.repositoryId()).resolve("revisions").resolve(context.revision());
    }

    private void replace(SourceRepositoryState state) {
        try {
            Path stateFile = published.resolve(state.repositoryId()).resolve("state.json");
            DurableSourceFiles.ensureDirectories(published, stateFile.getParent(),
                    DurableSourceFiles.Visibility.PUBLISHED);
            DurableSourceFiles.atomicBytes(stateFile, mapper.writeValueAsBytes(state), MAX_STATE,
                    DurableSourceFiles.Visibility.PUBLISHED);
        } catch (IOException exception) {
            throw new IllegalStateException("source state could not be committed", exception);
        }
    }
}
