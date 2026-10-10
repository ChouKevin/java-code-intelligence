package com.java.semantic.indexer.index;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.source.SourceCapacityGuard;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState.Receipt;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Runs on the existing dispatcher; committed publication always outranks later failures. */
public final class SemanticIndexManager {
    private final FileSemanticIndexJobStore jobs;
    private final IndexSourceAccess sources;
    private final IndexBuildExecutor builder;
    private final ResolutionInputStore inputs;
    private final IndexPublicationStore publications;
    private final SourceCapacityGuard capacity;
    private final Path staging;
    private final Path sourceRoot;

    public SemanticIndexManager(FileSemanticIndexJobStore jobs, IndexSourceAccess sources, IndexBuildExecutor builder,
            IndexPublicationStore publications, RepositoryProperties properties, ResolutionInputStore inputs) {
        this.jobs = jobs;
        this.sources = sources;
        this.builder = builder;
        this.publications = publications;
        this.inputs = inputs;
        capacity = new SourceCapacityGuard(properties);
        staging = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize().resolve("index/staging");
        sourceRoot = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
    }

    public SemanticIndexJob execute(SemanticIndexJob job) {
        RepositoryId repository = new RepositoryId(job.repositoryId());
        IndexJobId id = new IndexJobId(job.jobId());
        Path stage = staging.resolve(job.indexGeneration());
        try {
            capacity.check();
            Path lockPath = sourceRoot.resolve(job.repositoryId()).resolve("read.lock");
            IndexFiles.safe(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                    FileLock guard = channel.lock(0, Long.MAX_VALUE, true)) {
                IndexBuildInput input = sources.currentInput(new SourceContext(job.repositoryId(), job.revision()), job.indexGeneration());
                if (!input.originFingerprint().equals(job.originFingerprint())
                        || !input.sourceManifestDigest().equals(job.sourceManifestDigest())
                        || !input.sourceManifest().inventoryDigest().equals(job.inventoryDigest())) {
                    throw new IllegalStateException("accepted index source changed");
                }
                IndexManifest manifest = builder.build(input, job, stage, capacity::check);
                capacity.check();
                Receipt receipt = publications.publish(job, stage, manifest);
                SemanticIndexJob complete = jobs.complete(repository, id, receipt);
                inputs.cleanupTerminal(complete);
                return complete;
            }
        } catch (IOException | RuntimeException failure) {
            // Child cleanup restores cancellation before returning. Settle durable
            // ownership with interruptible file IO, then restore the caller's signal.
            boolean interrupted = Thread.interrupted();
            try {
                Optional<Receipt> committed = publications.receipt(job);
                if (committed.isPresent()) {
                    SemanticIndexJob complete = jobs.complete(repository, id, committed.orElseThrow());
                    inputs.cleanupTerminal(complete);
                    return complete;
                }
                String code = failure instanceof SourceCapacityGuard.CapacityException refused ? refused.failureCode()
                        : failure instanceof IndexBuildExecutor.WorkerFailure worker ? worker.failureCode()
                        : failure instanceof PlatformIdentity.EnvironmentChangedException ? "ENVIRONMENT_CHANGED"
                        : failure instanceof IndexFailure index ? index.code().name() : "INDEX_UNAVAILABLE";
                SemanticIndexJob failed = jobs.fail(repository, id, code);
                cleanupFailedStage(failed);
                inputs.cleanupTerminal(failed);
                return failed;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    /** Only a durable FAILED job authorizes removing its own never-published staging leaf. */
    public void cleanupFailedStage(SemanticIndexJob job) {
        if (job.phase() != SemanticIndexJob.Phase.FAILED) return;
        if (inputs.hasLiveWorker(new RepositoryId(job.repositoryId()))) return;
        Path stage = staging.resolve(job.indexGeneration());
        try {
            IndexFiles.safe(stage);
            if (!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) return;
            if (!Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid index staging leaf");
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(stage)) {
                for (Path file : entries) {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe staging entry");
                }
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(stage)) {
                for (Path file : entries) Files.delete(file);
            }
            Files.delete(stage);
        } catch (IOException failure) {
            throw IndexFiles.unavailable(failure);
        }
    }
}
