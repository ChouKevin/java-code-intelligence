package com.java.semantic.query.application;

import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.mongodb.client.MongoClients;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class ReviewSelectionContractIT extends PublishedMongoITSupport {
    private static final String REVISION_A = "a".repeat(40);
    private static final String REVISION_B = "b".repeat(40);
    private static final String REVIEW_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void selects_the_ready_side_fixed_generation_after_current_rebuilds_the_same_sha() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "review_selection");
            SealedGeneration a = seedGeneration(template, REVISION_A, "g-a", "1".repeat(64));
            SealedGeneration b = seedGeneration(template, REVISION_B, "g-b", "2".repeat(64));
            seedGeneration(template, REVISION_A, "g-a-rebuilt", "3".repeat(64));
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("currentPointer",
                    pointer(REVISION_A, "g-a-rebuilt", "3".repeat(64))));
            seedReadyReview(template, a, b);

            ConfiguredReadPolicy policy = policy();
            ReviewGenerationSelector selector = new ReviewGenerationSelector(
                    new ReviewManifestReadService(template, policy, Duration.ofSeconds(2)), guard(template, policy));

            ReviewSelection selected = selector.select(new RepositoryId("orders"), new ReviewId(REVIEW_ID), ReviewSide.A,
                    new RepositoryRevision(REVISION_A));

            assertThat(selected.selected().generationId().value()).isEqualTo("g-a");
            assertThatThrownBy(() -> selector.select(new RepositoryId("orders"), new ReviewId(REVIEW_ID), ReviewSide.A,
                    new RepositoryRevision(REVISION_B))).isInstanceOf(ReviewContextMismatchException.class);
        }
    }

    @Test
    void exposes_review_lifecycle_only_after_repository_visibility_and_rejects_malformed_ready_membership() {
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4")) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "review_lifecycle");
            ReviewManifestReadService reader = new ReviewManifestReadService(template, policy(), Duration.ofSeconds(2));
            RepositoryId repository = new RepositoryId("orders");
            ReviewId review = new ReviewId(REVIEW_ID);

            assertThatThrownBy(() -> reader.requireReady(repository, review)).isInstanceOf(ReviewNotFoundException.class);
            template.getCollection("index_jobs").insertOne(new Document("repoId", "orders").append("operation", "REVIEW")
                    .append("review", new Document("reviewId", REVIEW_ID)).append("phase", "PREPARING"));
            assertThatThrownBy(() -> reader.requireReady(repository, review)).isInstanceOf(ReviewNotReadyException.class);
            template.getCollection("index_jobs").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("phase", "FAILED")));
            assertThatThrownBy(() -> reader.requireReady(repository, review)).isInstanceOf(ReviewFailedException.class);
            template.getCollection("review_manifests").insertOne(new Document("repoId", "orders").append("reviewId", REVIEW_ID)
                    .append("state", "UNKNOWN"));
            assertThatThrownBy(() -> reader.requireReady(repository, review)).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("review_manifests").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("state", "READY")));
            assertThatThrownBy(() -> reader.requireReady(repository, review)).isInstanceOf(IndexContractMismatchException.class);
            ReviewManifestReadService denied = new ReviewManifestReadService(template,
                    new ConfiguredReadPolicy(new com.java.semantic.query.config.ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of())),
                    Duration.ofSeconds(2));
            assertThatThrownBy(() -> denied.requireReady(repository, review)).isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    private static SealedGeneration seedGeneration(MongoTemplate template, String revision, String generationId, String digest) {
        SelectedGeneration selected = new SelectedGeneration(new RepositoryId("orders"), new RepositoryRevision(revision),
                new GenerationId(generationId), new ManifestDigest(digest));
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, "e".repeat(64), "e".repeat(64),
                "e".repeat(64), "e".repeat(64), List.of());
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, fingerprint.digest(),
                "SUCCESS", List.of(), new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        template.getCollection("generation_manifests").insertOne(new Document("repoId", "orders").append("sourceRevision", revision)
                .append("generationId", generationId).append("identityDigest", digest).append("writeState", "SEALED_VALID")
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("projectionVersions", projectionVersions())
                .append("analysisFingerprint", fingerprint.digest()).append("analysisInputs", template.getConverter().convertToMongoType(inputs))
                .append("analysisEvidence", template.getConverter().convertToMongoType(evidence)));
        return new SealedGeneration(selected, fingerprint, evidence);
    }

    private static void seedReadyReview(MongoTemplate template, SealedGeneration a, SealedGeneration b) {
        Document aEndpoint = new Document("generation", template.getConverter().convertToMongoType(a))
                .append("snapshotId", "aaaaaaaa-1111-1111-1111-111111111111");
        Document bEndpoint = new Document("generation", template.getConverter().convertToMongoType(b))
                .append("snapshotId", "bbbbbbbb-2222-2222-2222-222222222222");
        template.getCollection("review_manifests").insertOne(new Document("repoId", "orders").append("reviewId", REVIEW_ID)
                .append("ownerJobId", "review-job").append("reviewContractVersion", IndexSchemaContract.REVIEW_MANIFEST_VERSION)
                .append("state", "READY").append("comparisonType", "CURRENT_TO_COMMIT")
                .append("capturedBaseline", new Document("pointer", pointer(REVISION_A, "g-a", "1".repeat(64))).append("capturedAt", new Date()))
                .append("requestedRevision", REVISION_B).append("a", aEndpoint).append("b", bEndpoint)
                .append("comparisonId", "cccccccc-3333-3333-3333-333333333333").append("createdAt", new Date()).append("publishedAt", new Date()));
    }

    private static Document pointer(String revision, String generationId, String digest) {
        return new Document("revision", revision).append("generationId", generationId).append("manifestDigest", digest)
                .append("committedJobId", "review-job").append("publishedAt", new Date());
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }
}
