package com.java.semantic.indexer.build;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
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
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceStructure;
import com.mongodb.client.MongoClients;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            seedPointersAndReview(template);
            MongoGenerationWriter writer = new MongoGenerationWriter(template);
            GenerationValidator validator = new GenerationValidator(template);

            Document pointersBeforeReview = pointerState(template);
            IndexJob activeBefore = jobs.activateReviewTarget(new IndexJobId("review-job"), ReviewSide.BEFORE);
            SealedGeneration sealedBefore = seal(template, writer, validator, activeBefore);
            jobs.recordReviewSide(activeBefore.id(), ReviewSide.BEFORE, sealedBefore);
            assertThat(pointerState(template)).isEqualTo(pointersBeforeReview);
            IndexJob activeAfter = jobs.activateReviewTarget(activeBefore.id(), ReviewSide.AFTER);
            GenerationWriteContext after = context(activeAfter);
            writeGeneration(template, writer, activeAfter);
            GenerationWriteContext staleBefore = context(activeBefore);

            assertThat(template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("generationId", after.generationId().value())).first()
                    .getList("acknowledgedBatches", String.class)).isNotEmpty();
            assertThatThrownBy(() -> writer.insertManifest(staleBefore, manifest(activeBefore))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> writer.seal(staleBefore, sealedBefore.selected().manifestDigest().value())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> validator.recordValid(staleBefore,
                    new GenerationValidator.ValidationResult(sealedBefore.selected().manifestDigest(), Map.of(), List.of())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(pointerState(template)).isEqualTo(pointersBeforeReview);
        }
    }

    private static SealedGeneration seal(MongoTemplate template, MongoGenerationWriter writer, GenerationValidator validator, IndexJob job) {
        GenerationWriteContext context = context(job);
        TestPreparedAnalysis analysis = TestPreparedAnalysis.forSnapshot(new RepositorySnapshot(
                job.repositoryId(), Path.of("."), job.target().orElseThrow().revision()), new FullIndexPlan(Path.of("."), List.of()));
        writeGeneration(template, writer, job);
        SourceIndexBatch batch = FullIndexPublicationIT.validBatch(job.repositoryId(), job.target().orElseThrow().revision(),
                job.target().orElseThrow().generationId());
        FullIndexPlan plan = new FullIndexPlan(Path.of("."), List.of(new FullIndexPlan.SourceInput(batch.sourcePath(),
                Path.of(batch.sourcePath()), batch.sourceArtifact())));
        SourceEvidencePolicy policy = new SourceEvidencePolicy(1, List.of("src"),
                Set.of(batch.sourcePath()), Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        SourceSnapshotMembership snapshot = new GitEvidencePublicationStore(template).publishSourceSnapshot(job,
                job.target().orElseThrow().revision(), List.of(new GitSnapshotEntry(batch.sourcePath(), "100644",
                        "1".repeat(40), GitFileContentStatus.TEXT,
                        batch.sourceArtifact().utf8Content().getBytes(StandardCharsets.UTF_8))), policy, guide, Instant.now());
        writer.recordSourceMembership(context, snapshot, guide, policy, new SourceCoverage(1, 0, 0, 0),
                new SourceStructure(List.of("src"), Map.of(), Map.of()));
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
        TestPreparedAnalysis analysis = TestPreparedAnalysis.forSnapshot(new RepositorySnapshot(
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
        Document before = target("a", "review-before", 5L);
        Document after = target("b", "review-after", 6L);
        Document review = new Document("reviewId", "review-1")
                .append("selection", new Document("kind", "RANGE").append("beforeRevision", revision("a").value())
                        .append("afterRevision", revision("b").value()))
                .append("resolvedEndpoints", new Document("beforeRevision", revision("a").value())
                        .append("afterRevision", revision("b").value()).append("baselineRule", "DIRECT_RANGE"))
                .append("reservedTargets", new Document("before", before).append("after", after))
                .append("stage", ReviewPreparationStage.PREPARING_BEFORE.name());
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
