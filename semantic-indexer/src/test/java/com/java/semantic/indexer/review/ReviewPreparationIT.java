package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitComparisonChange;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSide;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Mongo review graph regressions driven through controlled semantic and Git preparation ports. */
@Tag("mongo-it")
class ReviewPreparationIT {
    @Test
    void ready_manifest_published_before_the_owner_stage_cas_recovers_to_complete() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            fixture.template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", prepared.job.id().value()),
                    new Document("$set", new Document("review.stage", "VALIDATING")));

            fixture.jobs.reconcileCommittedJobs();

            assertThat(fixture.jobs.find(prepared.job.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()).a().orElseThrow().generation().selected())
                    .isEqualTo(prepared.captured.selected());
        }
    }

    @Test
    void forged_ready_membership_is_unavailable_and_recovery_fails_its_owner() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            fixture.template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateOne(new Document("reviewId", prepared.reviewId().value()),
                    new Document("$set", new Document("comparisonId", "f".repeat(36))));

            assertThatThrownBy(() -> fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()))
                    .isInstanceOf(IllegalStateException.class);
            fixture.jobs.reconcileCommittedJobs();
            fixture.jobs.failUnreconciledRunningJobs();

            assertThat(fixture.jobs.find(prepared.job.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
        }
    }

    @Test
    void missing_snapshot_file_keeps_review_preparing_when_ready_publication_is_retried() {
        assertPublicationRefusesTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES)
                .deleteOne(new Document("snapshotId", prepared.currentSnapshotId())));
    }

    @Test
    void missing_snapshot_chunk_keeps_review_preparing_when_ready_publication_is_retried() {
        assertPublicationRefusesTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS)
                .deleteOne(new Document("snapshotId", prepared.currentSnapshotId())));
    }

    @Test
    void missing_comparison_patch_keeps_review_preparing_when_ready_publication_is_retried() {
        assertPublicationRefusesTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES)
                .deleteOne(new Document("comparisonId", prepared.comparisonId())));
    }

    @Test
    void preparation_keeps_captured_a_after_current_moves_and_publishes_controlled_b_and_git_evidence() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);

            assertThat(prepared.endpointA()).isEqualTo(prepared.captured);
            assertThat(prepared.endpointB().selected().revision()).isEqualTo(fixture.requestedRevision);
            assertThat(fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()).a().orElseThrow().generation().selected())
                    .isEqualTo(prepared.captured.selected());
            assertThat(fixture.currentPointer().revision()).isEqualTo(fixture.movedCurrent.revision());
        }
    }

    @Test
    void incompatible_captured_a_builds_reserved_target_while_compatible_same_sha_alternate_keeps_its_original_owner() {
        try (MongoDBContainer first = container(); MongoDBContainer second = container()) {
            Fixture rebuild = fixture(first);
            PreparedReview rebuilt = rebuild.prepare(Selection.RESERVED_TARGET);
            assertThat(rebuilt.endpointA().selected().generationId()).isEqualTo(rebuilt.reservedA());
            assertThat(rebuild.generationOwner(rebuilt.reservedA())).isEqualTo(rebuilt.job.id().value());

            Fixture alternate = fixture(second);
            PreparedReview reused = alternate.prepare(Selection.ALTERNATE);
            assertThat(reused.endpointA().selected().generationId()).isEqualTo(alternate.alternateA.selected().generationId());
            assertThat(alternate.generationOwner(alternate.alternateA.selected().generationId())).isEqualTo("alternate-owner");
        }
    }

    private static void assertPublicationRefusesTampering(Tampering tampering) {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            fixture.resetForPublishRetry(prepared);
            tampering.apply(fixture, prepared);

            assertThatThrownBy(() -> fixture.reviews.publishReady(fixture.jobs.find(prepared.job.id()).orElseThrow()))
                    .isInstanceOf(ReviewPreparationException.class);
            assertThat(fixture.reviewState(prepared.reviewId())).isEqualTo("PREPARING");
        }
    }

    private static MongoDBContainer container() {
        MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
        container.start();
        return container;
    }

    private static Fixture fixture(MongoDBContainer container) {
        MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "semantic");
        new IndexSchemaBootstrap(template).bootstrap();
        return new Fixture(template);
    }

    private interface Tampering {
        void apply(Fixture fixture, PreparedReview prepared);
    }

    private enum Selection {
        CAPTURED,
        RESERVED_TARGET,
        ALTERNATE
    }

    private static final class Fixture {
        private static final RepositoryRevision CAPTURED_REVISION = new RepositoryRevision("a".repeat(40));
        private static final ManifestDigest CAPTURED_DIGEST = new ManifestDigest("a".repeat(64));
        private static final RepositoryRevision REQUESTED_REVISION = new RepositoryRevision("b".repeat(40));
        private final MongoTemplate template;
        private final MongoIndexJobStore jobs;
        private final ReviewPublicationStore reviews;
        private final RepositoryId repositoryId = RepositoryId.of("orders");
        private final PublishedGenerationPointer capturedPointer = new PublishedGenerationPointer(CAPTURED_REVISION,
                new GenerationId("g-captured"), CAPTURED_DIGEST, "captured-owner", Instant.parse("2026-09-19T00:00:00Z"));
        private final PublishedGenerationPointer movedCurrent = new PublishedGenerationPointer(new RepositoryRevision("c".repeat(40)),
                new GenerationId("g-moved"), new ManifestDigest("c".repeat(64)), "moved-owner", Instant.parse("2026-09-19T00:01:00Z"));
        private final SealedGeneration captured;
        private final SealedGeneration alternateA;
        private final RepositoryRevision requestedRevision = REQUESTED_REVISION;

        private Fixture(MongoTemplate template) {
            this.template = template;
            this.jobs = new MongoIndexJobStore(template);
            this.reviews = new ReviewPublicationStore(template, new ReviewReadinessValidator(template), jobs);
            captured = seedGeneration(CAPTURED_REVISION, capturedPointer.generationId(), CAPTURED_DIGEST, "captured-owner");
            alternateA = seedGeneration(CAPTURED_REVISION, new GenerationId("g-alternate"), new ManifestDigest("d".repeat(64)), "alternate-owner");
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", repositoryId.value())
                    .append("currentPointer", pointer(capturedPointer)));
        }

        private PreparedReview prepare(Selection selection) {
            IndexJob accepted = jobs.admitReview(repositoryId, requestedRevision);
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", repositoryId.value()),
                    new Document("$set", new Document("currentPointer", pointer(movedCurrent))));
            IndexJob running = jobs.startNextAccepted().orElseThrow();
            AtomicReference<SealedGeneration> a = new AtomicReference<>();
            AtomicReference<SealedGeneration> b = new AtomicReference<>();
            ReviewEndpointPreparationPort endpoints = (job, side, candidates) -> {
                SealedGeneration selected;
                if (side == ReviewSide.A) {
                    selected = switch (selection) {
                        case CAPTURED -> candidates.stream().filter(candidate -> candidate.selected().equals(captured.selected())).findFirst().orElseThrow();
                        case RESERVED_TARGET -> reservedGeneration(job);
                        case ALTERNATE -> candidates.stream().filter(candidate -> candidate.selected().equals(alternateA.selected())).findFirst().orElseThrow();
                    };
                    a.set(selected);
                } else {
                    selected = reservedGeneration(job);
                    b.set(selected);
                }
                return selected;
            };
            ReviewGitEvidencePort git = job -> new GitEvidencePublicationStore(template).publishComparison(job, comparison(), Instant.now(),
                    new GitEvidenceOwnership(com.java.semantic.model.git.GitPublicationScope.REVIEW, java.util.Optional.of(job.review().orElseThrow().reviewId())));
            new ReviewPreparationService(jobs, endpoints, git, reviews, template).prepare(running);
            IndexJob ready = jobs.find(accepted.id()).orElseThrow();
            return new PreparedReview(ready, ready.review().orElseThrow().reviewId(), a.get(), b.get(), captured,
                    ready.review().orElseThrow().comparisonId().orElseThrow().value(),
                    ready.review().orElseThrow().currentSnapshotId().orElseThrow().value());
        }

        private void resetForPublishRetry(PreparedReview prepared) {
            template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateOne(new Document("reviewId", prepared.reviewId().value()),
                    new Document("$set", new Document("state", "PREPARING")));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", prepared.job.id().value()),
                    new Document("$set", new Document("review.stage", "VALIDATING")));
        }

        private SealedGeneration reservedGeneration(IndexJob job) {
            IndexJobTarget target = job.target().orElseThrow();
            return seedGeneration(target.revision(), target.generationId(), new ManifestDigest(target.revision().value().substring(0, 1).repeat(64)), job.id().value());
        }

        private GitPreparedComparison comparison() {
            GitSnapshotEntry entry = new GitSnapshotEntry("README.md", "100644", "1".repeat(40), GitFileContentStatus.TEXT,
                    "review source\n".getBytes(StandardCharsets.UTF_8));
            GitComparisonChange change = new GitComparisonChange("change-0", com.java.semantic.model.git.GitChangeKind.MODIFY,
                    "README.md", "README.md", "100644", "100644", "1".repeat(40), "2".repeat(40),
                    "@@ -1 +1 @@\n-review source\n+review source\n", "AVAILABLE");
            return new GitPreparedComparison(CAPTURED_REVISION, REQUESTED_REVISION, GitComparisonAncestry.PREVIOUS_ANCESTOR,
                    List.of(entry), List.of(entry), List.of(change));
        }

        private SealedGeneration seedGeneration(RepositoryRevision revision, GenerationId generationId, ManifestDigest digest, String ownerJobId) {
            AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, "e".repeat(64), "e".repeat(64),
                    "e".repeat(64), "e".repeat(64), List.of(new AnalysisInputs.Project("project", "e".repeat(64), Map.of(), List.of(),
                    List.of(new AnalysisInputs.Root("src", "MAIN", true, List.of())), List.of(), List.of())));
            AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
            SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, fingerprint.digest(), "SUCCESS",
                    List.of(new SemanticAnalysisEvidence.ProjectProof("project", true, List.of("src"))),
                    new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", repositoryId.value())
                    .append("sourceRevision", revision.value()).append("generationId", generationId.value()).append("identityDigest", digest.value())
                    .append("ownerJobId", ownerJobId).append("writeState", "SEALED_VALID").append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                    .append("projectionVersions", projectionVersions()).append("analysisFingerprint", fingerprint.digest())
                    .append("analysisInputs", template.getConverter().convertToMongoType(inputs))
                    .append("analysisEvidence", template.getConverter().convertToMongoType(evidence)));
            return new SealedGeneration(new SelectedGeneration(repositoryId, revision, generationId, digest), fingerprint, evidence);
        }

        private String reviewState(com.java.semantic.model.review.ReviewId reviewId) {
            return template.getCollection(IndexCollections.REVIEW_MANIFESTS).find(new Document("reviewId", reviewId.value())).first().getString("state");
        }

        private PublishedGenerationPointer currentPointer() {
            Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", repositoryId.value())).first();
            Document pointer = repository.get("currentPointer", Document.class);
            return new PublishedGenerationPointer(new RepositoryRevision(pointer.getString("revision")), new GenerationId(pointer.getString("generationId")),
                    new ManifestDigest(pointer.getString("manifestDigest")), pointer.getString("committedJobId"), pointer.getDate("publishedAt").toInstant());
        }

        private String generationOwner(GenerationId generationId) {
            return template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("generationId", generationId.value())).first()
                    .getString("ownerJobId");
        }

    }

    private record PreparedReview(IndexJob job, com.java.semantic.model.review.ReviewId reviewId, SealedGeneration endpointA,
                                  SealedGeneration endpointB, SealedGeneration captured, String comparisonId, String currentSnapshotId) {
        private GenerationId reservedA() {
            return job.review().orElseThrow().reservedTargets().a().generationId();
        }
    }

    private static Document pointer(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }
}
