package com.java.semantic.indexer.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRepositoryState.CurrentPublication;
import com.java.semantic.model.support.ModelValidation;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Private lifecycle metadata. Missing data never grants deletion authority. */
@Component
public final class SourceRetentionStore {
    public static final Duration RETENTION = Duration.ofDays(30);
    private static final long MAX_RETENTION = 4L * 1024 * 1024;
    private static final long MAX_INTENT = 4096;
    private final Path admin;
    private final Path published;
    private final ObjectMapper mapper;
    private final RepositoryRegistry registry;

    public SourceRetentionStore(RepositoryProperties properties, ObjectMapper mapper, RepositoryRegistry registry) {
        admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        published = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
        this.mapper = mapper;
        this.registry = registry;
    }

    public synchronized void initialize(RepositoryId id, SourceRepositoryState state) {
        String origin = registry.origin(id);
        try {
            Path lock = published.resolve(id.value()).resolve(SourceRepositoryState.READ_LOCK_FILE_NAME);
            Path record = file(id, "retention.json");
            boolean hasLock = Files.exists(lock, LinkOption.NOFOLLOW_LINKS);
            boolean hasRecord = Files.exists(record, LinkOption.NOFOLLOW_LINKS);
            if (!hasLock && !hasRecord) {
                if (!state.published().isEmpty() || advertised(id)
                        || !originOnly(record.getParent()) || !originOnly(lock.getParent())
                        || nonempty(admin.resolve("jobs").resolve(id.value()))) {
                    throw new IOException("populated lifecycle cannot be adopted");
                }
                DurableSourceFiles.ensureDirectories(admin, record.getParent(), DurableSourceFiles.Visibility.PRIVATE);
                DurableSourceFiles.ensureDirectories(published, lock.getParent(), DurableSourceFiles.Visibility.PUBLISHED);
                try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) {
                    Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("rw-r--r--"));
                    channel.force(true);
                }
                DurableSourceFiles.forceDirectory(lock.getParent());
                save(new RetentionState(1, id.value(), origin, Map.of()));
            } else if (!hasLock || !hasRecord) {
                throw new IOException("initialized lifecycle is incomplete");
            }
            requireLock(id);
            load(id);
            pendingDeletion(id);
        } catch (IOException exception) {
            throw new IllegalStateException("source lifecycle unavailable", exception);
        }
    }

    public synchronized void beforePublication(RepositoryId id, RepositoryRevision next) {
        requireLock(id);
        pendingDeletion(id).filter(intent -> intent.revision().equals(next.value())).ifPresent(intent -> {
            throw new IllegalStateException("revision has pending deletion");
        });
        RetentionState state = load(id);
        if (state.retiredAt().containsKey(next.value())) {
            Map<String, Instant> entries = new HashMap<>(state.retiredAt());
            entries.remove(next.value());
            save(new RetentionState(1, id.value(), state.originFingerprint(), entries));
        }
    }

    public synchronized void afterPublication(RepositoryId id, Optional<CurrentPublication> previous, CurrentPublication next) {
        if (previous.isEmpty() || previous.orElseThrow().revision().equals(next.revision())) return;
        RetentionState state = load(id);
        Map<String, Instant> entries = new HashMap<>(state.retiredAt());
        entries.remove(next.revision());
        entries.put(previous.orElseThrow().revision(), next.publishedAt());
        save(new RetentionState(1, id.value(), state.originFingerprint(), entries));
    }

    public synchronized Map<String, Instant> reconcile(RepositoryId id, SourceRepositoryState state, Instant observedAt) {
        requireLock(id);
        RetentionState old = load(id);
        Map<String, Instant> entries = new HashMap<>(old.retiredAt());
        Optional<DeleteIntent> pending = pendingDeletion(id);
        entries.keySet().removeIf(sha -> !state.published().containsKey(sha)
                && pending.filter(intent -> intent.revision().equals(sha)).isEmpty());
        for (String sha : state.published().keySet()) {
            if (state.current().filter(current -> current.revision().equals(sha)).isPresent()) entries.remove(sha);
            else entries.putIfAbsent(sha, observedAt);
        }
        if (!entries.equals(old.retiredAt())) save(new RetentionState(1, id.value(), old.originFingerprint(), entries));
        return Map.copyOf(entries);
    }

    public synchronized Optional<DeleteIntent> pendingDeletion(RepositoryId id) {
        Path path = file(id, "pending-delete.json");
        checkParents(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            requireRegular(path);
            DeleteIntent intent = mapper.readValue(DurableSourceFiles.boundedRead(path, MAX_INTENT), DeleteIntent.class);
            requireIdentity(id, intent.repositoryId(), intent.originFingerprint());
            return Optional.of(intent);
        } catch (IOException exception) {
            throw new IllegalStateException("pending deletion unavailable", exception);
        }
    }

    public synchronized void beginDeletion(DeleteIntent intent) {
        RepositoryId id = new RepositoryId(intent.repositoryId());
        requireIdentity(id, intent.repositoryId(), intent.originFingerprint());
        load(id);
        Optional<DeleteIntent> previous = pendingDeletion(id);
        if (previous.isPresent()) {
            if (!previous.orElseThrow().equals(intent)) throw new IllegalStateException("repository already has deletion intent");
            return;
        }
        write(file(id, "pending-delete.json"), intent, MAX_INTENT);
    }

    public synchronized void finishDeletion(DeleteIntent intent) {
        RepositoryId id = new RepositoryId(intent.repositoryId());
        requirePending(intent);
        RetentionState old = load(id);
        Map<String, Instant> entries = new HashMap<>(old.retiredAt());
        entries.remove(intent.revision());
        save(new RetentionState(1, id.value(), old.originFingerprint(), entries));
        removeIntent(id);
    }

    public synchronized void cancelDeletion(DeleteIntent intent) {
        requirePending(intent);
        removeIntent(new RepositoryId(intent.repositoryId()));
    }

    public void requireLock(RepositoryId id) {
        registry.require(id);
        Path lock = published.resolve(id.value()).resolve(SourceRepositoryState.READ_LOCK_FILE_NAME);
        try {
            if (!Files.isDirectory(published, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(lock.getParent(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe lock parent");
            requireRegular(lock);
        } catch (IOException exception) {
            throw new IllegalStateException("source read lock unavailable", exception);
        }
    }

    private RetentionState load(RepositoryId id) {
        Path path = file(id, "retention.json");
        checkParents(path);
        try {
            requireRegular(path);
            RetentionState state = mapper.readValue(DurableSourceFiles.boundedRead(path, MAX_RETENTION), RetentionState.class);
            requireIdentity(id, state.repositoryId(), state.originFingerprint());
            return state;
        } catch (IOException exception) {
            throw new IllegalStateException("retirement metadata unavailable", exception);
        }
    }

    private void requireIdentity(RepositoryId id, String repository, String origin) {
        if (!id.value().equals(repository)) throw new IllegalStateException("lifecycle identity mismatch");
        registry.requireOrigin(id, origin);
    }
    private void requirePending(DeleteIntent intent) {
        if (!pendingDeletion(new RepositoryId(intent.repositoryId())).filter(intent::equals).isPresent())
            throw new IllegalStateException("pending deletion identity changed");
    }
    private void save(RetentionState state) { write(file(new RepositoryId(state.repositoryId()), "retention.json"), state, MAX_RETENTION); }
    private void write(Path path, Object value, long limit) {
        checkParents(path);
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) requireRegular(path);
            DurableSourceFiles.atomicBytes(path, mapper.writeValueAsBytes(value), limit, DurableSourceFiles.Visibility.PRIVATE);
        } catch (IOException exception) { throw new IllegalStateException("lifecycle metadata could not be committed", exception); }
    }
    private void removeIntent(RepositoryId id) {
        Path path = file(id, "pending-delete.json");
        checkParents(path);
        try {
            requireRegular(path);
            Files.delete(path);
            DurableSourceFiles.forceDirectory(path.getParent());
        } catch (IOException exception) { throw new IllegalStateException("deletion intent could not be cleared", exception); }
    }
    private Path file(RepositoryId id, String name) { return admin.resolve("repositories").resolve(id.value()).resolve(name); }
    private void checkParents(Path path) {
        Path parent = admin;
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("unsafe lifecycle root");
        for (Path segment : admin.relativize(path.getParent())) {
            parent = parent.resolve(segment);
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("unsafe lifecycle parent");
        }
    }
    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("nonregular lifecycle file");
    }
    private static boolean nonempty(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return true;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) { return entries.iterator().hasNext(); }
    }
    private static boolean originOnly(Path namespace) throws IOException {
        if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)) return false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(namespace)) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().equals("origin.sha256")) return false;
            }
        }
        return true;
    }
    private boolean advertised(RepositoryId id) throws IOException {
        Path path = published.resolve("repositories.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        requireRegular(path);
        SourceRepositoryDescriptor[] descriptors = mapper.readValue(DurableSourceFiles.boundedRead(path, 1024 * 1024), SourceRepositoryDescriptor[].class);
        for (SourceRepositoryDescriptor descriptor : descriptors) if (descriptor.repositoryId().equals(id.value())) return true;
        return false;
    }

    public record RetentionState(int formatVersion, String repositoryId, String originFingerprint, Map<String, Instant> retiredAt) {
        public RetentionState {
            ModelValidation.require(formatVersion == 1, "unsupported lifecycle format");
            repositoryId = new RepositoryId(repositoryId).value();
            originFingerprint = ModelValidation.sha256(originFingerprint, "origin fingerprint");
            retiredAt = Map.copyOf(Objects.requireNonNull(retiredAt));
            for (Map.Entry<String, Instant> entry : retiredAt.entrySet()) {
                RepositoryRevision.ofSha(entry.getKey());
                Objects.requireNonNull(entry.getValue());
            }
        }
    }
    public record DeleteIntent(int formatVersion, String repositoryId, String revision, String originFingerprint,
            String manifestDigest, String gcRunId) {
        public DeleteIntent {
            ModelValidation.require(formatVersion == 1, "unsupported lifecycle format");
            repositoryId = new RepositoryId(repositoryId).value();
            revision = RepositoryRevision.ofSha(revision).value();
            originFingerprint = ModelValidation.sha256(originFingerprint, "origin fingerprint");
            manifestDigest = ModelValidation.sha256(manifestDigest, "manifest digest");
            ModelValidation.require(UUID.fromString(gcRunId).toString().equals(gcRunId), "invalid GC run identity");
        }
    }
}
