package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.support.ModelValidation;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;

/** Durable ownership precedes copies. Cleanup never adopts unknown leaves or follows links. */
public final class ResolutionInputStore {
    private final Path admin;
    private final Path root;
    private final Path cleanupRoot;
    private final Path sourcePublished;
    private final Path indexPublished;
    private final ObjectMapper mapper;
    private final PlatformIdentity platform;
    private final RepositoryRegistry registry;
    public ResolutionInputStore(RepositoryProperties properties, ObjectMapper mapper, PlatformIdentity platform, RepositoryRegistry registry) {
        admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        root = admin.resolve("index/inputs");
        cleanupRoot = admin.resolve("index/input-cleanup");
        sourcePublished = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
        indexPublished = Path.of(properties.getIndexPublishedRoot()).toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper);
        this.platform = Objects.requireNonNull(platform);
        this.registry = Objects.requireNonNull(registry);
    }
    public record OwnedInput(int formatVersion, SourceContext context, String originFingerprint, String jobId,
            String requestId, String generation, String sourceManifestDigest, String inventoryDigest) {
        public OwnedInput {
            ModelValidation.require(formatVersion == 1, "invalid input owner format");
            context = Objects.requireNonNull(context);
            originFingerprint = ModelValidation.sha256(originFingerprint, "input origin");
            jobId = new IndexJobId(jobId).value();
            requestId = new PreparationRequestId(requestId).value();
            generation = IndexContract.requireGeneration(generation);
            sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "input source manifest");
            inventoryDigest = ModelValidation.sha256(inventoryDigest, "input inventory");
        }
    }
    public record OwnedTemporary(int formatVersion, OwnedInput owner, String temporaryRoot, String allocationId) {
        public OwnedTemporary {
            ModelValidation.require(formatVersion == 1, "invalid temporary owner format");
            owner = Objects.requireNonNull(owner);
            Path parent = Path.of(temporaryRoot);
            ModelValidation.require(parent.isAbsolute() && parent.normalize().toString().equals(temporaryRoot), "invalid temporary root");
            allocationId = IndexContract.requireGeneration(allocationId);
        }
        public Path directory() { return Path.of(temporaryRoot).resolve("index-worker-" + owner.jobId() + "-" + allocationId); }
    }

    /** Write-ahead identity lives on durable private storage, allocation stays on executable parent temp. */
    public WorkerTemporary prepareWorkerTemporary(SemanticIndexJob job) {
        OwnedInput owner = descriptor(job);
        Path leaf = directory(job);
        try (WorkerLease lease = tryLease(leaf)) {
            if (Objects.isNull(lease)) throw new IOException("worker lease busy");
            Path record = leaf.resolve("worker-temp.json");
            if (Files.exists(record, LinkOption.NOFOLLOW_LINKS)) throw new IOException("worker temporary already allocated");
            OwnedTemporary temporary = new OwnedTemporary(1, owner,
                    Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString(), UUID.randomUUID().toString());
            requireTemporaryPath(temporary);
            IndexFiles.safe(temporary.directory());
            if (Files.exists(temporary.directory(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("temporary identity already exists");
            byte[] bytes = mapper.writeValueAsBytes(temporary);
            DurableSourceFiles.atomicBytes(record, bytes, IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            Files.createDirectory(temporary.directory(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            DurableSourceFiles.forceDirectory(temporary.directory().getParent());
            DurableSourceFiles.atomicBytes(temporary.directory().resolve("owner.json"), bytes,
                    IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            return new WorkerTemporary(this, job, temporary.directory());
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    public static final class WorkerTemporary implements AutoCloseable {
        private final ResolutionInputStore store;
        private final SemanticIndexJob job;
        private final Path path;
        private WorkerTemporary(ResolutionInputStore store, SemanticIndexJob job, Path path) {
            this.store = store; this.job = job; this.path = path;
        }
        public Path path() { return path; }
        @Override public void close() {
            // Child has been reaped, but cancellation may leave the caller interrupted.
            boolean interrupted = Thread.interrupted();
            try { store.cleanupWorkerTemporary(job); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private void requireTemporaryPath(OwnedTemporary temporary) throws IOException {
        Path path = temporary.directory();
        if (path.startsWith(sourcePublished) || path.startsWith(indexPublished) || path.startsWith(root)
                || path.startsWith(cleanupRoot)) throw new IOException("temporary path overlaps managed publication or input");
        registry.requireOrigin(new RepositoryId(temporary.owner().context().repositoryId()), temporary.owner().originFingerprint());
    }

    /** Child validates the exact durable allocation, never a prefix or caller-supplied native path. */
    static void requireWorkerTemporary(Path leaf, OwnedInput owner) throws IOException {
        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        OwnedTemporary temporary = mapper.readValue(IndexFiles.read(leaf.resolve("worker-temp.json"), IndexFiles.MAX_MANIFEST), OwnedTemporary.class);
        if (!temporary.owner().equals(owner)
                || !temporary.directory().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                || !temporary.directory().equals(Path.of(System.getProperty("org.sqlite.tmpdir")).toAbsolutePath().normalize())
                || !mapper.readValue(IndexFiles.read(temporary.directory().resolve("owner.json"), IndexFiles.MAX_MANIFEST), OwnedTemporary.class).equals(temporary))
            throw new IOException("worker temporary ownership mismatch");
    }

    private void cleanupWorkerTemporary(SemanticIndexJob job) {
        OwnedInput owner = descriptor(job);
        try (WorkerLease lease = tryLease(directory(job))) {
            if (Objects.isNull(lease)) throw new IOException("worker lease busy");
            removeTemporary(directory(job), owner);
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    private void removeTemporary(Path leaf, OwnedInput owner) throws IOException {
        Path record = leaf.resolve("worker-temp.json");
        if (!Files.exists(record, LinkOption.NOFOLLOW_LINKS)) return;
        OwnedTemporary temporary = mapper.readValue(IndexFiles.read(record, IndexFiles.MAX_MANIFEST), OwnedTemporary.class);
        if (!temporary.owner().equals(owner)) throw new IOException("temporary input identity mismatch");
        requireTemporaryPath(temporary);
        Path path = temporary.directory();
        IndexFiles.safe(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path marker = path.resolve("owner.json");
            boolean marked = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
            if (marked && !mapper.readValue(IndexFiles.read(marker, IndexFiles.MAX_MANIFEST), OwnedTemporary.class).equals(temporary))
                throw new IOException("temporary marker mismatch");
            List<Path> payloads = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            || (!marked && !entry.getFileName().toString().matches("\\.source-.+\\.tmp")))
                        throw new IOException("unsafe temporary allocation");
                    if (!entry.equals(marker)) payloads.add(entry);
                }
            }
            for (Path payload : payloads) Files.delete(payload);
            // Before marker commit, only an empty allocation or its atomic-write residue is owned.
            Files.deleteIfExists(marker);
            DurableSourceFiles.forceDirectory(path);
            Files.delete(path);
            DurableSourceFiles.forceDirectory(path.getParent());
        }
        Files.delete(record);
        DurableSourceFiles.forceDirectory(leaf);
    }
    public IndexAnalysisIntent freeze(IndexBuildInput input, IndexJobId jobId, PreparationRequestId requestId,
            RepositoryProperties.RelationsConfig settings, Runnable capacityCheck) {
        OwnedInput owner = new OwnedInput(1, input.context(), input.originFingerprint(), jobId.value(), requestId.value(),
                input.generation(), input.sourceManifestDigest(), input.sourceManifest().inventoryDigest());
        Path directory = root.resolve(jobId.value());
        try {
            IndexFiles.safe(directory);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("input identity already exists");
            DurableSourceFiles.ensureDirectories(admin, directory, DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.atomicBytes(directory.resolve("descriptor.json"), mapper.writeValueAsBytes(owner), IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            Files.createFile(directory.resolve("worker.lock"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (FileChannel lock = FileChannel.open(directory.resolve("worker.lock"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { lock.force(true); }
            DurableSourceFiles.forceDirectory(directory);
            if (!settings.isEnabled()) return IndexAnalysisIntent.syntax();
            List<String> roots = RelationContract.requireSourceRoots(settings.getSourceRoots());
            for (String sourceRoot : roots) {
                Path admitted = input.tree().resolve(sourceRoot);
                IndexFiles.safe(admitted);
                if (SourcePathPolicy.isExcluded(sourceRoot) || !Files.isDirectory(admitted, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("source root is not admitted");
            }
            List<RepositoryProperties.DependencyConfig> configured = List.copyOf(settings.getDependencies());
            if (configured.size() > RelationContract.MAX_ARTIFACTS) throw new IOException("too many dependencies");
            List<ResolutionPlan.Dependency> dependencies = new ArrayList<>();
            long total = 0;
            for (int order = 0; order < configured.size(); order++) {
                RepositoryProperties.DependencyConfig dependency = configured.get(order);
                Path source = Path.of(dependency.getPath());
                if (!source.isAbsolute()) throw new IOException("dependency must be absolute");
                IndexFiles.safe(source);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("dependency is not ordinary");
                long size = Files.size(source);
                String name = String.format(Locale.ROOT, "dependency-%03d.jar", order);
                ResolutionPlan.Dependency frozen = new ResolutionPlan.Dependency(dependency.getId(), name, dependency.getSha256(), size, order);
                if (dependencies.stream().anyMatch(existing -> existing.id().equals(frozen.id()))) throw new IOException("duplicate dependency id");
                total = Math.addExact(total, size);
                if (total > RelationContract.MAX_TOTAL_ARTIFACT_BYTES) throw new IOException("dependency budget exceeded");
                copy(source, directory.resolve(name), frozen, capacityCheck);
                dependencies.add(frozen);
            }
            ResolutionPlan plan = new ResolutionPlan(1, input.context(), input.originFingerprint(), input.sourceManifestDigest(),
                    input.sourceManifest().inventoryDigest(), IndexContract.relationsProfile(), platform.current(), roots, dependencies);
            byte[] canonical = mapper.writeValueAsBytes(plan);
            capacityCheck.run();
            DurableSourceFiles.atomicBytes(directory.resolve("plan.json"), canonical, IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.forceDirectory(directory);
            capacityCheck.run();
            return new IndexAnalysisIntent(plan.profile(), Optional.of(DurableSourceFiles.sha256(canonical)));
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
    private static void copy(Path source, Path target, ResolutionPlan.Dependency expected, Runnable capacityCheck) throws IOException {
        MessageDigest digest = sha256();
        long copied = 0;
        capacityCheck.run();
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            ByteBuffer buffer = ByteBuffer.allocate(65536);
            while (input.read(buffer) >= 0) {
                buffer.flip();
                copied += buffer.remaining();
                if (copied > expected.byteLength()) throw new IOException("dependency size changed");
                digest.update(buffer.asReadOnlyBuffer());
                while (buffer.hasRemaining()) output.write(buffer);
                buffer.clear();
                capacityCheck.run();
            }
            if (copied != expected.byteLength() || !HexFormat.of().formatHex(digest.digest()).equals(expected.digest()))
                throw new IOException("dependency integrity mismatch");
            output.force(true);
        }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public Path directory(SemanticIndexJob job) { return root.resolve(job.jobId()); }
    public OwnedInput descriptor(SemanticIndexJob job) {
        OwnedInput owner = readOwner(directory(job));
        if (!owner.equals(new OwnedInput(1, new SourceContext(job.repositoryId(), job.revision()), job.originFingerprint(),
                job.jobId(), job.requestId(), job.indexGeneration(), job.sourceManifestDigest(), job.inventoryDigest())))
            throw new IllegalStateException("input ownership mismatch");
        return owner;
    }
    private OwnedInput readOwner(Path directory) {
        try {
            OwnedInput owner = mapper.readValue(IndexFiles.read(directory.resolve("descriptor.json"), IndexFiles.MAX_MANIFEST), OwnedInput.class);
            if (!directory.getFileName().toString().equals(owner.jobId())) throw new IOException("input leaf ownership mismatch");
            registry.requireOrigin(new RepositoryId(owner.context().repositoryId()), owner.originFingerprint());
            return owner;
        } catch (IOException | JacksonException | IllegalArgumentException exception) { throw IndexFiles.unavailable(exception); }
    }
    public ResolutionPlan load(SemanticIndexJob job) {
        descriptor(job);
        try {
            byte[] bytes = IndexFiles.read(directory(job).resolve("plan.json"), IndexFiles.MAX_MANIFEST);
            IndexAnalysisIntent analysis = job.analysis();
            if (!analysis.analysisPlanDigest().equals(Optional.of(DurableSourceFiles.sha256(bytes)))) throw new IOException("accepted plan changed");
            ResolutionPlan plan = mapper.readValue(bytes, ResolutionPlan.class);
            if (!plan.context().equals(new SourceContext(job.repositoryId(), job.revision())) || !plan.originFingerprint().equals(job.originFingerprint())
                    || !plan.sourceManifestDigest().equals(job.sourceManifestDigest()) || !plan.inventoryDigest().equals(job.inventoryDigest())
                    || !plan.profile().equals(analysis.profile())) throw new IOException("plan intent mismatch");
            for (ResolutionPlan.Dependency dependency : plan.dependencies()) {
                Path snapshot = directory(job).resolve(dependency.snapshotFile());
                IndexFiles.safe(snapshot);
                if (Files.size(snapshot) != dependency.byteLength() || !IndexFiles.digest(snapshot).equals(dependency.digest())) throw new IOException("accepted snapshot changed");
            }
            platform.requireSame(plan.platform());
            return plan;
        } catch (IOException | JacksonException | IllegalArgumentException exception) { throw IndexFiles.unavailable(exception); }
    }
    /** Legacy syntax jobs still need an owned physical-worker fence, without a Relations plan. */
    public void ensureWorkerInput(SemanticIndexJob job) {
        if (Files.exists(directory(job), LinkOption.NOFOLLOW_LINKS)) { descriptor(job); return; }
        if (job.formatVersion() != 1) throw new IllegalStateException("accepted input leaf missing");
        try {
            Path leaf = directory(job);
            IndexFiles.safe(leaf);
            DurableSourceFiles.ensureDirectories(admin, leaf, DurableSourceFiles.Visibility.PRIVATE);
            OwnedInput owner = new OwnedInput(1, new SourceContext(job.repositoryId(), job.revision()), job.originFingerprint(),
                    job.jobId(), job.requestId(), job.indexGeneration(), job.sourceManifestDigest(), job.inventoryDigest());
            DurableSourceFiles.atomicBytes(leaf.resolve("descriptor.json"), mapper.writeValueAsBytes(owner), IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            Files.createFile(leaf.resolve("worker.lock"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            DurableSourceFiles.forceTree(leaf);
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
    public void requireAccepted(SemanticIndexJob job) {
        if (job.formatVersion() == 1) return;
        descriptor(job);
        try {
            Path lock = directory(job).resolve("worker.lock");
            IndexFiles.safe(lock);
            if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) throw new IOException("accepted worker lease missing");
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
        if (job.analysis().analysisPlanDigest().isPresent()) load(job);
    }

    public void cleanupUnaccepted(IndexJobId jobId, PreparationRequestId request) {
        Path leaf = root.resolve(jobId.value());
        if (!Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)) return;
        OwnedInput owner;
        try { owner = readOwner(leaf); } catch (RuntimeException unknown) { return; }
        if (!owner.requestId().equals(request.value())) return;
        ensureOrphanLock(leaf);
        cleanup(leaf);
    }

    private static void ensureOrphanLock(Path leaf) {
        Path lock = leaf.resolve("worker.lock");
        if (Files.exists(lock, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            IndexFiles.safe(lock);
            Files.createFile(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            DurableSourceFiles.forceTree(leaf);
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
    public WorkerLease acquireWorker(SemanticIndexJob job) { return acquireWorker(directory(job), descriptor(job)); }
    /** Independent child acquisition; descriptor must be the parent's accepted, immutable identity. */
    public static WorkerLease acquireWorker(Path directory, OwnedInput owner) {
        try {
            IndexFiles.safe(directory);
            if (!directory.getFileName().toString().equals(owner.jobId())) throw new IOException("worker leaf mismatch");
            ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            if (!mapper.readValue(IndexFiles.read(directory.resolve("descriptor.json"), IndexFiles.MAX_MANIFEST), OwnedInput.class).equals(owner))
                throw new IOException("worker ownership changed");
            WorkerLease lease = tryLease(directory);
            if (Objects.isNull(lease)) throw new IOException("worker lease busy");
            return lease;
        } catch (IOException | JacksonException | IllegalArgumentException exception) { throw IndexFiles.unavailable(exception); }
    }
    private static WorkerLease tryLease(Path directory) throws IOException {
        Path path = directory.resolve("worker.lock");
        IndexFiles.safe(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("worker lease missing");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (Objects.nonNull(lock)) return new WorkerLease(channel, lock);
        } catch (OverlappingFileLockException busy) { /* Same JVM lease is equally busy. */ }
        catch (IOException exception) { channel.close(); throw exception; }
        channel.close();
        return null;
    }
    public static final class WorkerLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private WorkerLease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }
    public boolean hasLiveWorker(RepositoryId repository) {
        for (Path leaf : leaves()) {
            OwnedInput owner;
            try { owner = readOwner(leaf); } catch (RuntimeException unknown) { continue; }
            if (!owner.context().repositoryId().equals(repository.value())) continue;
            if (!Files.exists(leaf.resolve("worker.lock"), LinkOption.NOFOLLOW_LINKS)) continue;
            try (WorkerLease lease = tryLease(leaf)) { if (Objects.isNull(lease)) return true; }
            catch (IOException exception) { throw IndexFiles.unavailable(exception); }
        }
        return false;
    }
    public void cleanupTerminal(SemanticIndexJob job) {
        if (job.phase() != SemanticIndexJob.Phase.COMPLETE && job.phase() != SemanticIndexJob.Phase.FAILED) return;
        Path leaf = directory(job);
        Path intent = cleanupRoot.resolve(job.jobId() + ".json");
        if (Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) {
            OwnedInput owner = readCleanupOwner(intent);
            if (!owner.equals(new OwnedInput(1, new SourceContext(job.repositoryId(), job.revision()), job.originFingerprint(),
                    job.jobId(), job.requestId(), job.indexGeneration(), job.sourceManifestDigest(), job.inventoryDigest())))
                throw new IllegalStateException("cleanup job identity mismatch");
            finishCleanup(owner);
            return;
        }
        if (!Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)) return;
        try { descriptor(job); } catch (RuntimeException unknown) { return; }
        if (!Files.isRegularFile(leaf.resolve("worker.lock"), LinkOption.NOFOLLOW_LINKS)) return;
        cleanup(leaf);
    }
    private boolean cleanup(Path leaf) {
        try (WorkerLease lease = tryLease(leaf)) {
            if (Objects.isNull(lease)) return false;
            OwnedInput owner = readOwner(leaf);
            if (!knownMembers(leaf)) return false;
            Path intent = cleanupRoot.resolve(owner.jobId() + ".json");
            DurableSourceFiles.ensureDirectories(admin, cleanupRoot, DurableSourceFiles.Visibility.PRIVATE);
            if (Files.exists(intent, LinkOption.NOFOLLOW_LINKS)) {
                if (!readCleanupOwner(intent).equals(owner)) throw new IOException("cleanup authority changed");
            } else DurableSourceFiles.atomicBytes(intent, mapper.writeValueAsBytes(owner),
                    IndexFiles.MAX_MANIFEST, DurableSourceFiles.Visibility.PRIVATE);
            deleteOwnedLeaf(leaf, owner);
            Files.delete(intent);
            DurableSourceFiles.forceDirectory(cleanupRoot);
            return true;
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    private boolean knownMembers(Path leaf) throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(leaf)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !(name.equals("descriptor.json")
                        || name.equals("worker.lock") || name.equals("worker-temp.json") || name.equals("plan.json")
                        || name.equals("build-request.json") || name.matches("dependency-[0-9]{3}\\.jar")
                        || name.matches("\\.source-.+\\.tmp"))) return false;
            }
        }
        return true;
    }

    private OwnedInput readCleanupOwner(Path intent) {
        try {
            OwnedInput owner = mapper.readValue(IndexFiles.read(intent, IndexFiles.MAX_MANIFEST), OwnedInput.class);
            if (!intent.getFileName().toString().equals(owner.jobId() + ".json")) throw new IOException("cleanup path identity mismatch");
            registry.requireOrigin(new RepositoryId(owner.context().repositoryId()), owner.originFingerprint());
            return owner;
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    /** External authority remains until both lock and leaf are durably gone. */
    private void finishCleanup(OwnedInput owner) {
        Path leaf = root.resolve(owner.jobId());
        Path intent = cleanupRoot.resolve(owner.jobId() + ".json");
        try {
            IndexFiles.safe(leaf);
            if (Files.exists(leaf, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(leaf.resolve("descriptor.json"), LinkOption.NOFOLLOW_LINKS) && !readOwner(leaf).equals(owner))
                    throw new IOException("cleanup input identity changed");
                if (!knownMembers(leaf)) return;
                boolean locked = Files.exists(leaf.resolve("worker.lock"), LinkOption.NOFOLLOW_LINKS);
                if (!locked && Files.exists(leaf.resolve("descriptor.json"), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("cleanup lease missing before descriptor deletion");
                try (WorkerLease lease = locked ? tryLease(leaf) : null) {
                    if (locked && Objects.isNull(lease)) throw new IllegalStateException("owned cleanup worker has not settled");
                    deleteOwnedLeaf(leaf, owner);
                }
            }
            Files.delete(intent);
            DurableSourceFiles.forceDirectory(cleanupRoot);
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }

    private void deleteOwnedLeaf(Path leaf, OwnedInput owner) throws IOException {
        removeTemporary(leaf, owner);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(leaf)) {
            for (Path member : files) {
                String name = member.getFileName().toString();
                if (!name.equals("descriptor.json") && !name.equals("worker.lock")) Files.delete(member);
            }
        }
        Files.deleteIfExists(leaf.resolve("descriptor.json"));
        DurableSourceFiles.forceDirectory(leaf);
        Files.deleteIfExists(leaf.resolve("worker.lock"));
        DurableSourceFiles.forceDirectory(leaf);
        Files.delete(leaf);
        DurableSourceFiles.forceDirectory(root);
    }

    private List<Path> leaves() {
        try {
            IndexFiles.safe(root);
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
            List<Path> leaves = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) leaves.add(entry);
            }
            return leaves;
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
    }
    /** Settle only proven owned workers, never signal an unproven PID. Dispatcher starts afterward. */
    public void recover(FileSemanticIndexJobStore jobs) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        try {
            IndexFiles.safe(cleanupRoot);
            if (Files.exists(cleanupRoot, LinkOption.NOFOLLOW_LINKS)) {
                try (DirectoryStream<Path> intents = Files.newDirectoryStream(cleanupRoot, "*.json")) {
                    for (Path intent : intents) {
                        OwnedInput owner = readCleanupOwner(intent);
                        Optional<SemanticIndexJob> job = jobs.find(new RepositoryId(owner.context().repositoryId()),
                                new PreparationRequestId(owner.requestId()));
                        if (job.isPresent()) {
                            SemanticIndexJob accepted = job.orElseThrow();
                            if (!owner.equals(new OwnedInput(1, new SourceContext(accepted.repositoryId(), accepted.revision()),
                                    accepted.originFingerprint(), accepted.jobId(), accepted.requestId(), accepted.indexGeneration(),
                                    accepted.sourceManifestDigest(), accepted.inventoryDigest())))
                                throw new IOException("cleanup accepted identity mismatch");
                            if (accepted.phase() != SemanticIndexJob.Phase.COMPLETE && accepted.phase() != SemanticIndexJob.Phase.FAILED)
                                throw new IOException("cleanup targets active work");
                        }
                        finishCleanup(owner);
                    }
                }
            }
        } catch (IOException exception) { throw IndexFiles.unavailable(exception); }
        for (Path leaf : leaves()) {
            OwnedInput owner;
            try { owner = readOwner(leaf); } catch (RuntimeException unknown) { continue; }
            Optional<SemanticIndexJob> job = jobs.find(new RepositoryId(owner.context().repositoryId()), new PreparationRequestId(owner.requestId()));
            if (job.isEmpty()) ensureOrphanLock(leaf);
            else if (!Files.isRegularFile(leaf.resolve("worker.lock"), LinkOption.NOFOLLOW_LINKS)) continue;
            while (true) {
                try (WorkerLease lease = tryLease(leaf)) { if (Objects.nonNull(lease)) break; }
                catch (IOException exception) { throw IndexFiles.unavailable(exception); }
                if (System.nanoTime() >= deadline) throw new IllegalStateException("owned worker has not settled");
                try { Thread.sleep(25); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("worker settlement interrupted", exception); }
            }
            if (job.isEmpty()) cleanup(leaf);
            else {
                try { descriptor(job.orElseThrow()); } catch (RuntimeException unknown) { continue; }
                cleanupTerminal(job.orElseThrow());
            }
        }
    }
}
