package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.SourceCapacityGuard;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Dedicated child entrypoint. Never initializes Spring, dispatches jobs, or publishes. */
public final class IndexWorkerMain {
    static final int CAPACITY_EXCEEDED_EXIT = 10;
    static final int CAPACITY_UNAVAILABLE_EXIT = 11;
    static final int ENVIRONMENT_CHANGED_EXIT = 12;
    // Process-owned guards intentionally remain reachable until OS process teardown.
    // Closing them at the end of run would expose a live child as an idle repository.
    private static ResolutionInputStore.WorkerLease workerLease;
    private static FileChannel sourceChannel;
    private static FileLock sourceGuard;

    public record BuildRequest(IndexBuildInput input, SemanticIndexJob job, String stage, String sourceRoot,
            String adminRoot, String indexRoot, long maximumManagedBytes, long minimumFreeBytes) { }
    private IndexWorkerMain() { }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IOException("worker requires one owned descriptor");
            run(Path.of(args[0]).toAbsolutePath().normalize());
            System.exit(0);
        } catch (Exception | LinkageError failure) {
            String code = failure instanceof SourceCapacityGuard.CapacityException refused ? refused.failureCode()
                    : failure instanceof PlatformIdentity.EnvironmentChangedException ? "ENVIRONMENT_CHANGED" : "INDEX_UNAVAILABLE";
            System.err.println(code);
            int exit = switch (code) {
                case "CAPACITY_EXCEEDED" -> CAPACITY_EXCEEDED_EXIT;
                case "CAPACITY_UNAVAILABLE" -> CAPACITY_UNAVAILABLE_EXIT;
                case "ENVIRONMENT_CHANGED" -> ENVIRONMENT_CHANGED_EXIT;
                default -> 1;
            };
            System.exit(exit);
        }
    }

    private static void run(Path descriptor) throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        Path directory = descriptor.getParent();
        if (!descriptor.getFileName().toString().equals("descriptor.json")) throw new IOException("invalid descriptor name");
        ResolutionInputStore.OwnedInput owner = mapper.readValue(IndexFiles.read(descriptor, IndexFiles.MAX_MANIFEST), ResolutionInputStore.OwnedInput.class);
        workerLease = ResolutionInputStore.acquireWorker(directory, owner);
        ResolutionInputStore.requireWorkerTemporary(directory, owner);
        System.out.println("READY");
        System.out.flush();
        if (System.in.read() != 1) return;
        Thread parentMonitor = new Thread(() -> {
            try { while (System.in.read() >= 0) { } }
            catch (IOException ignored) { /* Loss of parent pipe is also parent death. */ }
            Runtime.getRuntime().halt(2);
        }, "index-worker-parent-monitor");
        parentMonitor.setDaemon(true);
        parentMonitor.start();
        BuildRequest request = mapper.readValue(IndexFiles.read(directory.resolve("build-request.json"), IndexFiles.MAX_MANIFEST), BuildRequest.class);
        IndexBuildInput input = request.input();
        SemanticIndexJob job = request.job();
        if (!owner.equals(new ResolutionInputStore.OwnedInput(1, input.context(), input.originFingerprint(), job.jobId(),
                job.requestId(), input.generation(), input.sourceManifestDigest(), input.sourceManifest().inventoryDigest()))
                || !input.generation().equals(job.indexGeneration()) || !input.context().repositoryId().equals(job.repositoryId())
                || !input.context().revision().equals(job.revision()) || !input.sourceManifestDigest().equals(job.sourceManifestDigest())
                || !input.sourceManifest().inventoryDigest().equals(job.inventoryDigest()) || !input.originFingerprint().equals(job.originFingerprint()))
            throw new IOException("worker request ownership mismatch");
        RepositoryProperties properties = new RepositoryProperties();
        properties.setSourceAdminRoot(request.adminRoot()); properties.setSourcePublishedRoot(request.sourceRoot());
        properties.setIndexPublishedRoot(request.indexRoot());
        properties.getSourceCapacity().setMaxManagedBytes(request.maximumManagedBytes());
        properties.getSourceCapacity().setMinFreeBytes(request.minimumFreeBytes());
        Path sourceRoot = Path.of(request.sourceRoot()).toAbsolutePath().normalize();
        Path revision = sourceRoot.resolve(job.repositoryId()).resolve("revisions").resolve(job.revision());
        if (!input.tree().equals(revision.resolve("tree")) || !input.inventory().equals(revision.resolve("inventory.jsonl"))
                || !Path.of(request.stage()).equals(Path.of(request.adminRoot()).resolve("index/staging").resolve(job.indexGeneration())))
            throw new IOException("worker source namespace mismatch");
        Path lock = sourceRoot.resolve(job.repositoryId()).resolve("read.lock");
        IndexFiles.safe(lock);
        sourceChannel = FileChannel.open(lock, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        sourceGuard = sourceChannel.lock(0, Long.MAX_VALUE, true);
        Optional<ResolutionPlan> plan = Optional.empty();
        if (IndexContract.supportsRelations(job.analysis().profile())) {
            byte[] bytes = IndexFiles.read(directory.resolve("plan.json"), IndexFiles.MAX_MANIFEST);
            if (!job.analysis().analysisPlanDigest().equals(Optional.of(DurableSourceFiles.sha256(bytes))))
                throw new IOException("worker plan digest mismatch");
            ResolutionPlan accepted = mapper.readValue(bytes, ResolutionPlan.class);
            if (!accepted.context().equals(input.context()) || !accepted.profile().equals(job.analysis().profile())
                    || !accepted.originFingerprint().equals(input.originFingerprint())
                    || !accepted.sourceManifestDigest().equals(input.sourceManifestDigest())
                    || !accepted.inventoryDigest().equals(job.inventoryDigest())) throw new IOException("worker plan identity mismatch");
            new PlatformIdentity().requireSame(accepted.platform());
            for (ResolutionPlan.Dependency dependency : accepted.dependencies()) {
                Path artifact = directory.resolve(dependency.snapshotFile());
                if (java.nio.file.Files.size(artifact) != dependency.byteLength() || !IndexFiles.digest(artifact).equals(dependency.digest()))
                    throw new IOException("worker artifact identity mismatch");
            }
            plan = Optional.of(accepted);
        }
        new SqliteIndexBuilder(mapper, new JavaSyntaxExtractor()).build(input, Path.of(request.stage()),
                new SourceCapacityGuard(properties)::check, job.analysis(), plan, directory);
    }
}
