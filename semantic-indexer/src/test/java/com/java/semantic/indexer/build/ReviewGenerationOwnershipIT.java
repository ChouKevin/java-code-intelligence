package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSide;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class ReviewGenerationOwnershipIT {
    @Test
    void stale_a_cannot_mutate_after_b_activates_and_review_sealing_leaves_pointers_unchanged() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            seedPointersAndReview(template);
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            GenerationValidator validator = new GenerationValidator(template);

            Document pointersBeforeReview = pointerState(template);
            IndexJob activeA = jobs.activateReviewTarget(new IndexJobId("review-job"), ReviewSide.A);
            SealedGeneration sealedA = seal(template, writer, validator, activeA);
            jobs.recordReviewSide(activeA.id(), ReviewSide.A, sealedA);
            assertThat(pointerState(template)).isEqualTo(pointersBeforeReview);
            IndexJob activeB = jobs.activateReviewTarget(activeA.id(), ReviewSide.B);
            GenerationWriteContext b = context(activeB);
            writeGeneration(template, writer, activeB);
            GenerationWriteContext staleA = context(activeA);

            assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("generationId", b.generationId().value())).first()
                    .getList("acknowledgedBatches", String.class)).isNotEmpty();
            assertThatThrownBy(() -> writer.insertManifest(staleA, manifest(activeA))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> writer.seal(staleA, sealedA.selected().manifestDigest().value())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> validator.recordValid(staleA,
                    new GenerationValidator.ValidationResult(sealedA.selected().manifestDigest(), Map.of(), List.of())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(pointerState(template)).isEqualTo(pointersBeforeReview);
        }
    }

    private static SealedGeneration seal(MongoTemplate template, MongoGenerationWriter writer, GenerationValidator validator, IndexJob job) {
        GenerationWriteContext context = context(job);
        TestPreparedAnalysis analysis = TestPreparedAnalysis.forSnapshot(new com.java.semantic.repository.domain.RepositorySnapshot(
                job.repositoryId(), Path.of("."), job.target().orElseThrow().revision()), new FullIndexPlan(Path.of("."), List.of()));
        writeGeneration(template, writer, job);
        SourceIndexBatch batch = FullIndexPublicationIT.validBatch(job.repositoryId(), job.target().orElseThrow().revision(),
                job.target().orElseThrow().generationId());
        FullIndexPlan plan = new FullIndexPlan(Path.of("."), List.of(new FullIndexPlan.SourceInput(batch.sourcePath(),
                Path.of(batch.sourcePath()), batch.sourceArtifact())));
        GenerationValidator.ValidationResult result = validator.validate(context, job.target().orElseThrow().revision(),
                job.target().orElseThrow().revision(), plan);
        assertThat(result.valid()).isTrue();
        validator.recordValid(context, result);
        writer.seal(context, result.identityDigest().value());
        return new SealedGeneration(new SelectedGeneration(job.repositoryId(), job.target().orElseThrow().revision(),
                job.target().orElseThrow().generationId(), result.identityDigest()), analysis.fingerprint(), analysis.readinessEvidence());
    }

    private static void writeGeneration(MongoTemplate template, MongoGenerationWriter writer, IndexJob job) {
        GenerationWriteContext context = context(job);
        writer.insertManifest(context, manifest(job));
        TestPreparedAnalysis analysis = TestPreparedAnalysis.forSnapshot(new com.java.semantic.repository.domain.RepositorySnapshot(
                job.repositoryId(), Path.of("."), job.target().orElseThrow().revision()), new FullIndexPlan(Path.of("."), List.of()));
        writer.recordAnalysis(context, analysis.fingerprint(), analysis.readinessEvidence());
        new MongoIndexBatchWriter(writer, context, new SourceIndexBatchDocumentMapper(template.getConverter()))
                .write(FullIndexPublicationIT.validBatch(job.repositoryId(), job.target().orElseThrow().revision(), job.target().orElseThrow().generationId()));
    }

    private static GenerationWriteContext context(IndexJob job) {
        return new GenerationWriteContext(job.repositoryId(), job.target().orElseThrow().generationId(), job.id().value());
    }

    private static Document manifest(IndexJob job) {
        return new Document("sourceRevision", job.target().orElseThrow().revision().value()).append("writeState", GenerationWriteState.WRITING.name())
                .append("writeEpoch", 0L).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of());
    }

    private static void seedPointersAndReview(MongoTemplate template) {
        Document current = pointer("a", "current", "current-job");
        Document rollback = pointer("b", "rollback", "rollback-job");
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", "orders").append("currentPointer", current).append("rollbackPointer", rollback));
        Document a = target("a", "review-a", 5L);
        Document b = target("b", "review-b", 6L);
        Document review = new Document("reviewId", "review-1").append("baseline", new Document("pointer", current).append("capturedAt", new Date()))
                .append("requestedRevision", revision("b").value()).append("reservedTargets", new Document("a", a).append("b", b))
                .append("stage", ReviewPreparationStage.PREPARING_A.name());
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", "review-job").append("repoId", "orders")
                .append("active", true).append("phase", IndexJobPhase.RUNNING.name()).append("operation", IndexJobOperation.REVIEW.name())
                .append("rebuild", false).append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION).append("generationHighWatermark", 6L)
                .append("review", review).append("createdAt", Date.from(Instant.now())));
    }

    private static Document target(String revision, String generationId, long generation) {
        return new Document("revision", revision(revision).value()).append("generationId", generationId).append("generation", generation);
    }

    private static Document pointer(String revision, String generationId, String jobId) {
        return new Document("revision", revision(revision).value()).append("generationId", generationId).append("manifestDigest", revision.repeat(64))
                .append("committedJobId", jobId).append("publishedAt", Date.from(Instant.parse("2026-09-19T00:00:00Z")));
    }

    private static Document pointerState(MongoTemplate template) {
        Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", "orders")).first();
        return new Document("currentPointer", new Document(repository.get("currentPointer", Document.class)))
                .append("rollbackPointer", new Document(repository.get("rollbackPointer", Document.class)));
    }

    private static RepositoryRevision revision(String value) { return new RepositoryRevision(value.repeat(40)); }
}
