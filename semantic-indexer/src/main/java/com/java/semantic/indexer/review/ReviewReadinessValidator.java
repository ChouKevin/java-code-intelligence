package com.java.semantic.indexer.review;

import com.java.semantic.indexer.build.GenerationValidator;
import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/** Validates every persisted semantic and Git member before immutable review publication. */
@Component
public final class ReviewReadinessValidator {
    private final MongoTemplate template;
    private final GitEvidencePublicationStore gitEvidence;
    private final GenerationValidator generations;

    public ReviewReadinessValidator(MongoTemplate template) {
        this(template, new GitEvidencePublicationStore(template), new GenerationValidator(template));
    }

    @Autowired
    public ReviewReadinessValidator(MongoTemplate template, GitEvidencePublicationStore gitEvidence) {
        this(template, gitEvidence, new GenerationValidator(template));
    }

    ReviewReadinessValidator(MongoTemplate template, GitEvidencePublicationStore gitEvidence, GenerationValidator generations) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.gitEvidence = Objects.requireNonNull(gitEvidence, "Git evidence store is required");
        this.generations = Objects.requireNonNull(generations, "generation validator is required");
    }

    public ReviewManifestDocument validateReadyCandidate(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (!requiredJob.active() || requiredJob.phase() != IndexJobPhase.RUNNING) {
            throw mismatch("review readiness requires its active running owner");
        }
        return validateGraph(requiredJob);
    }

    /** Rechecks an already published manifest owner without treating post-publication completion as corruption. */
    public ReviewManifestDocument validatePublishedReady(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if ((requiredJob.phase() != IndexJobPhase.RUNNING || !requiredJob.active())
                && (requiredJob.phase() != IndexJobPhase.COMPLETE || requiredJob.active())) {
            throw mismatch("published review owner is not reconcilable");
        }
        return validateGraph(requiredJob);
    }

    private ReviewManifestDocument validateGraph(IndexJob job) {
        if (job.operation() != IndexJobOperation.REVIEW) {
            throw mismatch("review readiness requires a REVIEW owner");
        }
        ReviewJobPayload payload = job.review().orElseThrow(() -> mismatch("review payload is required"));
        if ((payload.stage() != ReviewPreparationStage.VALIDATING && payload.stage() != ReviewPreparationStage.READY)
                || payload.resolvedEndpoints().isEmpty() || payload.after().isEmpty() || payload.comparisonId().isEmpty()
                || payload.previousSnapshotId().isEmpty() || payload.currentSnapshotId().isEmpty()) {
            throw mismatch("review readiness requires resolved sides and all Git identities");
        }
        ResolvedReviewEndpoints resolved = payload.resolvedEndpoints().orElseThrow();
        if (resolved.beforeRevision().isPresent() != payload.before().isPresent()
                || !resolved.afterRevision().equals(payload.selection().afterRevision())) {
            throw mismatch("review semantic membership does not match resolved Git endpoints");
        }
        Optional<SealedGeneration> before = payload.before();
        SealedGeneration after = payload.after().orElseThrow();
        GitComparisonId comparisonId = payload.comparisonId().orElseThrow();
        GitSnapshotId previousSnapshotId = payload.previousSnapshotId().orElseThrow();
        GitSnapshotId currentSnapshotId = payload.currentSnapshotId().orElseThrow();
        before.ifPresent(generation -> {
            if (!generation.selected().revision().equals(resolved.beforeRevision().orElseThrow())) {
                throw mismatch("review before generation does not match resolved revision");
            }
            validateGeneration(job, generation, previousSnapshotId);
        });
        if (!after.selected().revision().equals(resolved.afterRevision())) {
            throw mismatch("review after generation does not match resolved revision");
        }
        validateGeneration(job, after, currentSnapshotId);
        validateGit(job, payload, comparisonId, previousSnapshotId, currentSnapshotId);
        return new ReviewManifestDocument(job.repositoryId(), payload.reviewId(), job.id().value(),
                IndexSchemaContract.REVIEW_MANIFEST_VERSION, ReviewState.READY, payload.selection(), Optional.of(resolved),
                before.map(generation -> new ReviewEndpoint(generation, previousSnapshotId)),
                Optional.of(new ReviewEndpoint(after, currentSnapshotId)),
                Optional.of(comparisonId), Instant.now(), Optional.of(Instant.now()), Optional.empty());
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
        if (!generations.validatePersistedSealed(generation.selected()).valid()) {
            throw mismatch("review generation persisted semantic graph does not match its sealed identity");
        }
        List<Document> sources = template.getCollection(IndexCollections.GENERATION_FILES).find(new Document("repoId", job.repositoryId().value())
                .append("generationId", generation.selected().generationId().value())).into(new ArrayList<>());
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
        ResolvedReviewEndpoints resolved = payload.resolvedEndpoints().orElseThrow();
        if (!Objects.equals(resolved.beforeRevision().map(revision -> revision.value()).orElse(null), comparison.getString("previous"))
                || !resolved.afterRevision().value().equals(comparison.getString("current"))
                || !resolved.baselineRule().name().equals(comparison.getString("baselineRule"))
                || !previousSnapshotId.value().equals(comparison.getString("previousSnapshotId"))
                || !currentSnapshotId.value().equals(comparison.getString("currentSnapshotId"))) {
            throw mismatch("review comparison endpoints or snapshots are incompatible");
        }
        validateSnapshot(job, payload.reviewId().value(), previousSnapshotId,
                resolved.beforeRevision().map(revision -> revision.value()));
        validateSnapshot(job, payload.reviewId().value(), currentSnapshotId, Optional.of(resolved.afterRevision().value()));
        try {
            gitEvidence.validateReadyReviewComparison(job.repositoryId(), comparisonId, previousSnapshotId, currentSnapshotId);
        } catch (RuntimeException exception) {
            throw mismatch("review Git evidence rows or chunks do not match their READY manifest");
        }
    }

    private void validateSnapshot(IndexJob job, String reviewId, GitSnapshotId snapshotId, Optional<String> revision) {
        Document snapshot = evidence(job, snapshotId.value(), "SNAPSHOT", reviewId);
        Number total = snapshot.get("total", Number.class);
        if (!Objects.equals(revision.orElse(null), snapshot.getString("revision"))
                || revision.isEmpty() && (Objects.nonNull(snapshot.getString("revision")) || Objects.isNull(total) || total.longValue() != 0L)
                || Objects.isNull(total) || total.longValue() < 0L
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
