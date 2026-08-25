package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobWorker;
import com.java.semantic.indexer.job.LeaseGuard;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryRevision;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.bson.Document;

/** Coordinates a complete, fenced full-index build; publication remains in the repository-pointer boundary. */
public final class IndexBuildService {
    private final FullIndexPlanner planner;
    private final RepositoryIndexExporter exporter;
    private final MongoGenerationWriter generationWriter;
    private final SourceIndexBatchDocumentMapper documentMapper;
    private final GenerationValidator validator;
    private final IndexJobWorker worker;
    private final CheckoutResolver checkedOutRepository;
    private final IncrementalGenerationBuilder incrementalBuilder;

    /** Adds Task-13 incremental assembly while preserving the existing full-build construction seam. */
    public IndexBuildService(FullIndexPlanner planner, RepositoryIndexExporter exporter,
                             MongoGenerationWriter generationWriter, SourceIndexBatchDocumentMapper documentMapper,
                             GenerationValidator validator, IndexJobWorker worker, CheckoutResolver checkedOutRepository,
                             IncrementalGenerationBuilder incrementalBuilder) {
        this.planner = Objects.requireNonNull(planner, "planner is required");
        this.exporter = Objects.requireNonNull(exporter, "exporter is required");
        this.generationWriter = Objects.requireNonNull(generationWriter, "generation writer is required");
        this.documentMapper = Objects.requireNonNull(documentMapper, "document mapper is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.worker = Objects.requireNonNull(worker, "worker is required");
        this.checkedOutRepository = Objects.requireNonNull(checkedOutRepository, "checked-out repository is required");
        this.incrementalBuilder = Objects.requireNonNull(incrementalBuilder, "incremental builder is required");
    }

    /** Executes checkout, plan, export, fenced writes, validation, sealing, publication and idempotent reconciliation. */
    public void build(IndexJob job) {
        build(job, () -> { });
    }

    /** The worker supplies a heartbeat-backed guard so lost claims stop before every visible build boundary. */
    public void build(IndexJob job, LeaseGuard guard) {
        Objects.requireNonNull(job, "job is required");
        Objects.requireNonNull(guard, "lease guard is required");
        MongoGenerationWriter.GenerationLease lease = lease(job);
        try {
            guard.requireHeld();
            CheckedOutRepository checkout = checkedOutRepository.checkout(job);
            if (!job.revision().equals(checkout.revision())) {
                throw new GenerationValidationException("CHECKOUT_CHANGED");
            }
            guard.requireHeld();
            FullIndexPlan plan = planner.plan(checkout.root());
            guard.requireHeld();
            insertWritingManifest(job, lease);
            guard.requireHeld();
            FullIndexPlan exportPlan = incrementalBuilder.assemble(job, lease, plan).exportPlan();
            List<SourceIndexBatch> batches = exporter.export(job.repositoryId(), job.revision(), job.generationId(), exportPlan);
            MongoIndexBatchWriter writer = new MongoIndexBatchWriter(generationWriter, lease, documentMapper);
            for (SourceIndexBatch batch : batches) {
                guard.requireHeld();
                writer.write(batch);
            }
            guard.requireHeld();
            CheckedOutRepository latestCheckout = checkedOutRepository.checkout(job);
            if (!checkout.root().equals(latestCheckout.root())) {
                throw new GenerationValidationException("CHECKOUT_ROOT_CHANGED");
            }
            GenerationValidator.ValidationResult result = validator.validate(lease, job.revision(), latestCheckout.revision());
            if (!result.valid()) {
                throw new GenerationValidationException(result.issues().getFirst().code());
            }
            guard.requireHeld();
            validator.recordValid(lease, result);
            Date currentClaimUntil = generationWriter.currentClaimUntil(lease);
            generationWriter.mirrorSealUntil(lease, currentClaimUntil);
            generationWriter.seal(lease, result.identityDigest().value());
            guard.requireHeld();
            if (!worker.publishBuild(job, result.identityDigest())) {
                throw new IllegalStateException("publication did not reconcile the committed job");
            }
        } catch (RuntimeException exception) {
            worker.failOrCancel(job, category(exception));
            throw exception;
        }
    }

    private void insertWritingManifest(IndexJob job, MongoGenerationWriter.GenerationLease lease) {
        generationWriter.insertManifest(new Document("repoId", job.repositoryId().value()).append("generationId", job.generationId().value())
                .append("sourceRevision", job.revision().value()).append("ownerJobId", lease.jobId())
                .append("ownerWorkerId", lease.workerId()).append("fence", lease.fence())
                .append("sealUntil", Date.from(job.claimUntil().orElseThrow())).append("writeState", GenerationWriteState.WRITING.name())
                .append("writeEpoch", 0L).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", projectionVersions()).append("identityDigest", "0".repeat(64))
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }

    private static MongoGenerationWriter.GenerationLease lease(IndexJob job) {
        return new MongoGenerationWriter.GenerationLease(job.repositoryId(), job.generationId(), job.id().value(),
                job.workerId().orElseThrow(), job.fence().orElseThrow().value());
    }

    private static IndexFailureCategory category(RuntimeException exception) {
        if (exception instanceof GenerationValidationException) {
            return IndexFailureCategory.VALIDATION_FAILED;
        }
        if (exception instanceof com.java.semantic.indexer.store.PublicationConflictException) {
            return IndexFailureCategory.PUBLICATION_CONFLICT;
        }
        return IndexFailureCategory.WORKER_INTERRUPTED;
    }

    @FunctionalInterface
    public interface CheckoutResolver {
        CheckedOutRepository checkout(IndexJob job);
    }

    public record CheckedOutRepository(Path root, RepositoryRevision revision) {
        public CheckedOutRepository {
            root = Objects.requireNonNull(root, "checkout root is required").toAbsolutePath().normalize();
            revision = Objects.requireNonNull(revision, "checkout revision is required");
        }
    }

    private static final class GenerationValidationException extends RuntimeException {
        private GenerationValidationException(String code) {
            super("generation validation failed: " + code);
        }
    }
}
