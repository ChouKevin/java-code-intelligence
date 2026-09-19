package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ReviewComparisonType;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Validates every persisted semantic and Git member before immutable review publication. */
@Component
public final class ReviewReadinessValidator {
    private final MongoTemplate template;

    public ReviewReadinessValidator(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public ReviewManifestDocument validateReadyCandidate(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW || !requiredJob.active()
                || requiredJob.phase() != IndexJobPhase.RUNNING) {
            throw mismatch("review readiness requires its active running owner");
        }
        ReviewJobPayload payload = requiredJob.review().orElseThrow(() -> mismatch("review payload is required"));
        if ((payload.stage() != ReviewPreparationStage.VALIDATING && payload.stage() != ReviewPreparationStage.READY)
                || payload.a().isEmpty() || payload.b().isEmpty() || payload.comparisonId().isEmpty()
                || payload.previousSnapshotId().isEmpty() || payload.currentSnapshotId().isEmpty()) {
            throw mismatch("review readiness requires both sealed sides and all Git identities");
        }
        SealedGeneration a = payload.a().orElseThrow();
        SealedGeneration b = payload.b().orElseThrow();
        GitComparisonId comparisonId = payload.comparisonId().orElseThrow();
        GitSnapshotId previousSnapshotId = payload.previousSnapshotId().orElseThrow();
        GitSnapshotId currentSnapshotId = payload.currentSnapshotId().orElseThrow();
        if (!a.selected().revision().equals(payload.baseline().pointer().revision())
                || !a.selected().generationId().equals(payload.baseline().pointer().generationId())
                || !a.selected().manifestDigest().equals(payload.baseline().pointer().manifestDigest())
                || !b.selected().revision().equals(payload.requestedRevision())) {
            throw mismatch("review sides do not match their admitted immutable revisions");
        }
        validateGeneration(requiredJob, a, previousSnapshotId);
        validateGeneration(requiredJob, b, currentSnapshotId);
        validateGit(requiredJob, payload, comparisonId, previousSnapshotId, currentSnapshotId);
        return new ReviewManifestDocument(requiredJob.repositoryId(), payload.reviewId(), requiredJob.id().value(),
                IndexSchemaContract.REVIEW_MANIFEST_VERSION, ReviewState.READY, ReviewComparisonType.CURRENT_TO_COMMIT,
                payload.baseline(), payload.requestedRevision(),
                java.util.Optional.of(new ReviewEndpoint(a, previousSnapshotId)), java.util.Optional.of(new ReviewEndpoint(b, currentSnapshotId)),
                java.util.Optional.of(comparisonId), Instant.now(), java.util.Optional.of(Instant.now()), java.util.Optional.empty());
    }

    private void validateGeneration(IndexJob job, SealedGeneration generation, GitSnapshotId snapshotId) {
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("repoId", job.repositoryId().value())
                .append("generationId", generation.selected().generationId().value())
                .append("sourceRevision", generation.selected().revision().value())
                .append("identityDigest", generation.selected().manifestDigest().value())
                .append("writeState", "SEALED_VALID")
                .append("analysisFingerprint", generation.fingerprint().digest())
                .append("analysisEvidence", new Document("$exists", true))).first();
        if (Objects.isNull(manifest) || !requiredProjections(manifest)) {
            throw mismatch("review generation is not a complete sealed semantic generation");
        }
        List<Document> sources = template.getCollection(IndexCollections.GENERATION_FILES).find(new Document("repoId", job.repositoryId().value())
                .append("generationId", generation.selected().generationId().value())).into(new java.util.ArrayList<>());
        for (Document source : sources) {
            Document snapshotFile = template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(new Document("repoId", job.repositoryId().value())
                    .append("snapshotId", snapshotId.value()).append("path", source.getString("sourcePath"))
                    .append("contentStatus", "TEXT")).first();
            if (Objects.isNull(snapshotFile) || !Objects.equals(source.getString("contentHash"), snapshotFile.getString("checksum"))) {
                throw mismatch("semantic source content does not match its exact Git snapshot");
            }
        }
    }

    private void validateGit(IndexJob job, ReviewJobPayload payload, GitComparisonId comparisonId,
                             GitSnapshotId previousSnapshotId, GitSnapshotId currentSnapshotId) {
        Document comparison = evidence(job, comparisonId.value(), "COMPARISON", payload.reviewId().value());
        if (!payload.baseline().pointer().revision().value().equals(comparison.getString("previous"))
                || !payload.requestedRevision().value().equals(comparison.getString("current"))
                || !previousSnapshotId.value().equals(comparison.getString("previousSnapshotId"))
                || !currentSnapshotId.value().equals(comparison.getString("currentSnapshotId"))) {
            throw mismatch("review comparison endpoints or snapshots are incompatible");
        }
        validateSnapshot(job, payload.reviewId().value(), previousSnapshotId, payload.baseline().pointer().revision().value());
        validateSnapshot(job, payload.reviewId().value(), currentSnapshotId, payload.requestedRevision().value());
    }

    private void validateSnapshot(IndexJob job, String reviewId, GitSnapshotId snapshotId, String revision) {
        Document snapshot = evidence(job, snapshotId.value(), "SNAPSHOT", reviewId);
        Number total = snapshot.get("total", Number.class);
        if (!revision.equals(snapshot.getString("revision")) || Objects.isNull(total) || total.longValue() < 0L
                || Objects.isNull(snapshot.getString("contentDigest")) || Objects.isNull(snapshot.get("contentCoverage", Document.class))) {
            throw mismatch("review snapshot is incomplete");
        }
    }

    private Document evidence(IndexJob job, String evidenceId, String kind, String reviewId) {
        Document evidence = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).find(new Document("repoId", job.repositoryId().value())
                .append("evidenceId", evidenceId).append("ownerJobId", job.id().value()).append("kind", kind)
                .append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION)
                .append("scope", "REVIEW").append("reviewId", reviewId)).first();
        if (Objects.isNull(evidence)) {
            throw mismatch("review Git evidence is missing or not owned by the active review");
        }
        return evidence;
    }

    private static boolean requiredProjections(Document manifest) {
        Object values = manifest.get("projectionVersions");
        if (!(values instanceof List<?> projections)) {
            return false;
        }
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream().allMatch(required -> projections.stream()
                .filter(Document.class::isInstance).map(Document.class::cast)
                .anyMatch(actual -> required.getKey().equals(actual.getString("name"))
                        && required.getValue().equals(actual.get("version", Number.class).intValue())));
    }

    private static ReviewPreparationException mismatch(String message) {
        return new ReviewPreparationException(IndexFailureCategory.REVIEW_EVIDENCE_MISMATCH, message);
    }
}
