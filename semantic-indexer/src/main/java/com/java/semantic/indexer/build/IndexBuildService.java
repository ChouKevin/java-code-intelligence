package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.IndexPublicationIntent;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.indexer.uat.PublicationGate;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.repository.RepositoryRevision;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.bson.Document;

/** Coordinates a complete build; IndexJobExecutor owns all terminal job transitions. */
public final class IndexBuildService {
    private final FullIndexPlanner planner;
    private final RepositoryIndexExporter exporter;
    private final MongoGenerationWriter generationWriter;
    private final SourceIndexBatchDocumentMapper documentMapper;
    private final GenerationValidator validator;
    private final IndexJobStore jobs;
    private final PublicationPort publication;
    private final PublicationGate publicationGate;
    private final CheckoutResolver checkedOutRepository;
    private final IncrementalGenerationBuilder incrementalBuilder;

    /** Adds incremental assembly and a bounded publication boundary to the full-build path. */
    public IndexBuildService(FullIndexPlanner planner, RepositoryIndexExporter exporter,
                             MongoGenerationWriter generationWriter, SourceIndexBatchDocumentMapper documentMapper,
                             GenerationValidator validator, CheckoutResolver checkedOutRepository,
                             IncrementalGenerationBuilder incrementalBuilder, IndexJobStore jobs, PublicationPort publication,
                             PublicationGate publicationGate) {
        this.planner = Objects.requireNonNull(planner, "planner is required");
        this.exporter = Objects.requireNonNull(exporter, "exporter is required");
        this.generationWriter = Objects.requireNonNull(generationWriter, "generation writer is required");
        this.documentMapper = Objects.requireNonNull(documentMapper, "document mapper is required");
        this.validator = Objects.requireNonNull(validator, "validator is required");
        this.checkedOutRepository = Objects.requireNonNull(checkedOutRepository, "checked-out repository is required");
        this.incrementalBuilder = Objects.requireNonNull(incrementalBuilder, "incremental builder is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.publication = Objects.requireNonNull(publication, "publication is required");
        this.publicationGate = Objects.requireNonNull(publicationGate, "publication gate is required");
    }

    /** Executes checkout, planning, export, validation, sealing, and pointer publication. */
    public void build(IndexJob job) {
        Objects.requireNonNull(job, "job is required");
        IndexPublicationIntent intent;
        try {
            intent = buildSealedGeneration(job);
        } catch (RuntimeException exception) {
            publicationGate.abortPublication();
            throw exception;
        }
        publicationGate.awaitPublication();
        publication.publish(new PublishGenerationCommand(job.repositoryId(), intent.targetRevision(), intent.targetGenerationId(),
                job.id().value(), intent.expectedParent(), intent.targetManifestDigest()));
    }

    private IndexPublicationIntent buildSealedGeneration(IndexJob job) {
        IndexJobTarget target = job.target().orElseThrow(() -> new IllegalArgumentException("BUILD requires a target"));
        GenerationWriteContext context = new GenerationWriteContext(job.repositoryId(), target.generationId(), job.id().value());
        generationWriter.verifySchemaBeforeGeneration();
        CheckedOutRepository checkout = checkedOutRepository.checkout(job);
        if (!target.revision().equals(checkout.revision())) {
            throw new GenerationValidationException("CHECKOUT_CHANGED");
        }
        FullIndexPlan plan = planner.plan(checkout.root());
        insertWritingManifest(job, context);
        FullIndexPlan exportPlan = incrementalBuilder.assemble(job, context, plan).exportPlan();
        List<SourceIndexBatch> batches = exporter.export(job.repositoryId(), target.revision(), target.generationId(), exportPlan);
        MongoIndexBatchWriter writer = new MongoIndexBatchWriter(generationWriter, context, documentMapper);
        for (SourceIndexBatch batch : batches) {
            writer.write(batch);
        }
        CheckedOutRepository latestCheckout = checkedOutRepository.checkout(job);
        if (!checkout.root().equals(latestCheckout.root())) {
            throw new GenerationValidationException("CHECKOUT_ROOT_CHANGED");
        }
        GenerationValidator.ValidationResult result = validator.validate(context, target.revision(), latestCheckout.revision());
        if (!result.valid()) {
            throw new GenerationValidationException(result.issues().getFirst().code());
        }
        validator.recordValid(context, result);
        generationWriter.seal(context, result.identityDigest().value());
        return jobs.prepareBuildPublication(job, result.identityDigest())
                .orElseThrow(() -> new IllegalStateException("publication precondition failed"));
    }

    private void insertWritingManifest(IndexJob job, GenerationWriteContext context) {
        IndexJobTarget target = job.target().orElseThrow(() -> new IllegalArgumentException("BUILD requires a target"));
        generationWriter.insertManifest(context, new Document("repoId", job.repositoryId().value()).append("generationId", target.generationId().value())
                .append("sourceRevision", target.revision().value()).append("ownerJobId", context.jobId())
                .append("writeState", GenerationWriteState.WRITING.name())
                .append("writeEpoch", 0L).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", projectionVersions()).append("identityDigest", "0".repeat(64))
                .append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
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

    /** Stable signal for checkout and generation validation failures. */
    public static final class GenerationValidationException extends RuntimeException {
        public GenerationValidationException(String code) {
            super("generation validation failed: " + code);
        }
    }
}
