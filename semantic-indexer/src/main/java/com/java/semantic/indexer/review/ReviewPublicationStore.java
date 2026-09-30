package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewState;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
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
        ReviewJobPayload payload = requiredJob.review().orElseThrow();
        ReviewManifestDocument manifest = new ReviewManifestDocument(requiredJob.repositoryId(), payload.reviewId(), requiredJob.id().value(),
                IndexSchemaContract.REVIEW_MANIFEST_VERSION, ReviewState.PREPARING, payload.selection(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Instant.now(),
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
                || !manifest.ownerJobId().equals(verified.ownerJobId()) || !manifest.selection().equals(verified.selection())
                || !manifest.resolvedEndpoints().equals(verified.resolvedEndpoints()) || !manifest.before().equals(verified.before())
                || !manifest.after().equals(verified.after()) || !manifest.comparisonId().equals(verified.comparisonId())) {
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
                .append("state", manifest.state().name()).append("selection", selectionDocument(manifest.selection()))
                .append("selectionKey", manifest.selection().selectionKey())
                .append("createdAt", Date.from(manifest.createdAt()));
    }

    private Document readyValues(ReviewManifestDocument manifest) {
        ReviewEndpoint after = manifest.after().orElseThrow();
        Document values = new Document("state", "READY")
                .append("resolvedEndpoints", resolvedDocument(manifest.resolvedEndpoints().orElseThrow()))
                .append("after", endpointDocument(after))
                .append("comparisonId", manifest.comparisonId().orElseThrow().value())
                .append("publishedAt", Date.from(manifest.publishedAt().orElseThrow()));
        manifest.before().ifPresent(before -> values.append("before", endpointDocument(before)));
        return values;
    }

    private Document endpointDocument(ReviewEndpoint endpoint) {
        return new Document("generation", template.getConverter().convertToMongoType(endpoint.generation()))
                .append("snapshotId", endpoint.snapshotId().value());
    }

    private ReviewManifestDocument fromReady(Document document) {
        Document selection = Objects.requireNonNull(document.get("selection", Document.class), "review selection is required");
        Document resolved = Objects.requireNonNull(document.get("resolvedEndpoints", Document.class), "resolved review endpoints are required");
        return new ReviewManifestDocument(RepositoryId.of(document.getString("repoId")), new ReviewId(document.getString("reviewId")),
                document.getString("ownerJobId"), document.get("reviewContractVersion", Number.class).intValue(), ReviewState.READY,
                selectionFrom(selection), Optional.of(resolvedFrom(resolved)),
                Optional.ofNullable(document.get("before", Document.class)).map(this::endpointFrom),
                Optional.of(endpointFrom(Objects.requireNonNull(document.get("after", Document.class), "review after endpoint is required"))),
                Optional.of(new GitComparisonId(document.getString("comparisonId"))), document.getDate("createdAt").toInstant(),
                Optional.of(document.getDate("publishedAt").toInstant()), Optional.empty());
    }

    private ReviewEndpoint endpointFrom(Document endpoint) {
        SealedGeneration generation = template.getConverter().read(SealedGeneration.class,
                Objects.requireNonNull(endpoint.get("generation", Document.class), "review generation is required"));
        return new ReviewEndpoint(generation, new GitSnapshotId(endpoint.getString("snapshotId")));
    }

    private static Document selectionDocument(ReviewSelection selection) {
        Document document = new Document("kind", selection.kind().name());
        if (selection.kind() == ReviewComparisonType.COMMIT) {
            document.append("revision", selection.afterRevision().value());
        } else {
            document.append("beforeRevision", selection.beforeRevision().orElseThrow().value())
                    .append("afterRevision", selection.afterRevision().value());
        }
        return document;
    }

    private static ReviewSelection selectionFrom(Document document) {
        ReviewComparisonType kind = ReviewComparisonType.valueOf(document.getString("kind"));
        return new ReviewSelection(kind, Optional.ofNullable(document.getString("beforeRevision")).map(RepositoryRevision::ofSha),
                RepositoryRevision.ofSha(document.getString(kind == ReviewComparisonType.COMMIT ? "revision" : "afterRevision")));
    }

    private static Document resolvedDocument(ResolvedReviewEndpoints endpoints) {
        Document document = new Document("afterRevision", endpoints.afterRevision().value())
                .append("baselineRule", endpoints.baselineRule().name());
        endpoints.beforeRevision().ifPresent(revision -> document.append("beforeRevision", revision.value()));
        return document;
    }

    private static ResolvedReviewEndpoints resolvedFrom(Document document) {
        return new ResolvedReviewEndpoints(Optional.ofNullable(document.getString("beforeRevision")).map(RepositoryRevision::ofSha),
                RepositoryRevision.ofSha(document.getString("afterRevision")),
                ReviewBaselineRule.valueOf(document.getString("baselineRule")));
    }
}
