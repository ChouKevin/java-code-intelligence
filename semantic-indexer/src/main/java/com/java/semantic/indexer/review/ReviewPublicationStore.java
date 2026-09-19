package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewState;
import com.java.semantic.model.repository.RepositoryId;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Owns the PREPARING-to-READY review manifest cut; it never mutates a repository pointer. */
@Component
public final class ReviewPublicationStore {
    private final MongoTemplate template;
    private final ReviewReadinessValidator readiness;
    private final IndexJobStore jobs;

    public ReviewPublicationStore(MongoTemplate template, ReviewReadinessValidator readiness, IndexJobStore jobs) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.readiness = Objects.requireNonNull(readiness, "review readiness validator is required");
        this.jobs = Objects.requireNonNull(jobs, "job store is required");
    }

    public ReviewManifestDocument begin(IndexJob job) {
        IndexJob requiredJob = requireReview(job);
        com.java.semantic.indexer.job.ReviewJobPayload payload = requiredJob.review().orElseThrow();
        ReviewManifestDocument manifest = new ReviewManifestDocument(requiredJob.repositoryId(), payload.reviewId(), requiredJob.id().value(),
                IndexSchemaContract.REVIEW_MANIFEST_VERSION, ReviewState.PREPARING, ReviewComparisonType.CURRENT_TO_COMMIT,
                payload.baseline(), payload.requestedRevision(), Optional.empty(), Optional.empty(), Optional.empty(), Instant.now(),
                Optional.empty(), Optional.empty());
        template.getCollection(IndexCollections.REVIEW_MANIFESTS).insertOne(preparingDocument(manifest));
        return manifest;
    }

    public ReviewManifestDocument publishReady(IndexJob job) {
        IndexJob requiredJob = requireReview(job);
        ReviewManifestDocument candidate = readiness.validateReadyCandidate(requiredJob);
        long updated = template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateOne(new Document("repoId", requiredJob.repositoryId().value())
                .append("reviewId", candidate.reviewId().value()).append("ownerJobId", requiredJob.id().value()).append("state", "PREPARING"),
                new Document("$set", readyValues(candidate))).getModifiedCount();
        if (updated != 1L) {
            throw new ReviewPreparationException(IndexFailureCategory.PUBLICATION_CONFLICT, "review publication ownership changed");
        }
        return candidate;
    }

    public void failUnpublished(IndexJob job, IndexFailureCategory category) {
        IndexJob requiredJob = requireReview(job);
        template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateMany(new Document("repoId", requiredJob.repositoryId().value())
                        .append("ownerJobId", requiredJob.id().value()).append("state", "PREPARING"),
                new Document("$set", new Document("state", "FAILED").append("failureCategory",
                        Objects.requireNonNull(category, "failure category is required").name())));
        template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).updateMany(new Document("repoId", requiredJob.repositoryId().value())
                        .append("ownerJobId", requiredJob.id().value()).append("state", "PREPARING"),
                new Document("$set", new Document("state", "FAILED")));
    }

    public ReviewManifestDocument findReady(RepositoryId repositoryId, ReviewId reviewId) {
        RepositoryId requiredRepository = Objects.requireNonNull(repositoryId, "repository id is required");
        ReviewId requiredReview = Objects.requireNonNull(reviewId, "review id is required");
        Document document = template.getCollection(IndexCollections.REVIEW_MANIFESTS).find(new Document("repoId", requiredRepository.value())
                .append("reviewId", requiredReview.value()).append("state", "READY")
                .append("reviewContractVersion", IndexSchemaContract.REVIEW_MANIFEST_VERSION)).first();
        if (Objects.isNull(document)) {
            throw new IllegalStateException("ready review was not found");
        }
        ReviewManifestDocument manifest;
        try {
            manifest = fromReady(document);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("ready review membership is malformed", exception);
        }
        IndexJob owner = jobs.find(new IndexJobId(manifest.ownerJobId()))
                .orElseThrow(() -> new IllegalStateException("ready review owner was not found"));
        ReviewManifestDocument verified = readiness.validatePublishedReady(owner);
        if (!manifest.repositoryId().equals(requiredRepository) || !manifest.reviewId().equals(requiredReview)
                || !manifest.ownerJobId().equals(verified.ownerJobId()) || !manifest.capturedBaseline().equals(verified.capturedBaseline())
                || !manifest.requestedRevision().equals(verified.requestedRevision()) || !manifest.a().equals(verified.a())
                || !manifest.b().equals(verified.b()) || !manifest.comparisonId().equals(verified.comparisonId())) {
            throw new IllegalStateException("ready review membership is incompatible");
        }
        return manifest;
    }

    private static IndexJob requireReview(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW) {
            throw new IllegalArgumentException("review publication requires a REVIEW job");
        }
        return requiredJob;
    }

    private static Document preparingDocument(ReviewManifestDocument manifest) {
        return new Document("repoId", manifest.repositoryId().value()).append("reviewId", manifest.reviewId().value())
                .append("ownerJobId", manifest.ownerJobId()).append("reviewContractVersion", manifest.reviewContractVersion())
                .append("state", manifest.state().name()).append("comparisonType", manifest.comparisonType().name())
                .append("capturedBaseline", baselineDocument(manifest.capturedBaseline()))
                .append("requestedRevision", manifest.requestedRevision().value()).append("createdAt", Date.from(manifest.createdAt()));
    }

    private Document readyValues(ReviewManifestDocument manifest) {
        ReviewEndpoint a = manifest.a().orElseThrow();
        ReviewEndpoint b = manifest.b().orElseThrow();
        return new Document("state", "READY").append("a", endpointDocument(a)).append("b", endpointDocument(b))
                .append("comparisonId", manifest.comparisonId().orElseThrow().value())
                .append("publishedAt", Date.from(manifest.publishedAt().orElseThrow()));
    }

    private Document endpointDocument(ReviewEndpoint endpoint) {
        return new Document("generation", template.getConverter().convertToMongoType(endpoint.generation()))
                .append("snapshotId", endpoint.snapshotId().value());
    }

    private ReviewManifestDocument fromReady(Document document) {
        Document baseline = Objects.requireNonNull(document.get("capturedBaseline", Document.class), "review baseline is required");
        Document pointer = Objects.requireNonNull(baseline.get("pointer", Document.class), "review pointer is required");
        com.java.semantic.model.index.PublishedGenerationPointer captured = new com.java.semantic.model.index.PublishedGenerationPointer(
                new com.java.semantic.model.repository.RepositoryRevision(pointer.getString("revision")),
                new com.java.semantic.model.index.GenerationId(pointer.getString("generationId")),
                new com.java.semantic.model.index.ManifestDigest(pointer.getString("manifestDigest")), pointer.getString("committedJobId"),
                pointer.getDate("publishedAt").toInstant());
        com.java.semantic.model.review.CapturedReviewBaseline reviewBaseline = new com.java.semantic.model.review.CapturedReviewBaseline(captured,
                baseline.getDate("capturedAt").toInstant());
        return new ReviewManifestDocument(RepositoryId.of(document.getString("repoId")), new ReviewId(document.getString("reviewId")),
                document.getString("ownerJobId"), document.get("reviewContractVersion", Number.class).intValue(), ReviewState.READY,
                ReviewComparisonType.valueOf(document.getString("comparisonType")), reviewBaseline,
                new com.java.semantic.model.repository.RepositoryRevision(document.getString("requestedRevision")),
                Optional.of(endpointFrom(document.get("a", Document.class))), Optional.of(endpointFrom(document.get("b", Document.class))),
                Optional.of(new com.java.semantic.model.git.GitComparisonId(document.getString("comparisonId"))), document.getDate("createdAt").toInstant(),
                Optional.of(document.getDate("publishedAt").toInstant()), Optional.empty());
    }

    private ReviewEndpoint endpointFrom(Document endpoint) {
        SealedGeneration generation = template.getConverter().read(SealedGeneration.class,
                Objects.requireNonNull(endpoint.get("generation", Document.class), "review generation is required"));
        return new ReviewEndpoint(generation, new com.java.semantic.model.git.GitSnapshotId(endpoint.getString("snapshotId")));
    }

    private static Document baselineDocument(com.java.semantic.model.review.CapturedReviewBaseline baseline) {
        com.java.semantic.model.index.PublishedGenerationPointer pointer = baseline.pointer();
        return new Document("pointer", new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()))).append("capturedAt", Date.from(baseline.capturedAt()));
    }
}
