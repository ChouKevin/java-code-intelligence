package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.review.ReviewSide;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Serially prepares the two admitted immutable review endpoints, then one review-owned Git comparison. */
@Service
public final class ReviewPreparationService {
    private final IndexJobStore jobs;
    private final ReviewEndpointPreparationPort endpointPreparation;
    private final ReviewGitEvidencePort reviewGitEvidence;
    private final ReviewPublicationStore reviews;
    private final MongoTemplate template;

    public ReviewPreparationService(IndexJobStore jobs, ReviewEndpointPreparationPort endpointPreparation,
                                    ReviewGitEvidencePort reviewGitEvidence, ReviewPublicationStore reviews, MongoTemplate template) {
        this.jobs = Objects.requireNonNull(jobs, "job store is required");
        this.endpointPreparation = Objects.requireNonNull(endpointPreparation, "endpoint preparation is required");
        this.reviewGitEvidence = Objects.requireNonNull(reviewGitEvidence, "review Git evidence is required");
        this.reviews = Objects.requireNonNull(reviews, "review publication store is required");
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public void prepare(IndexJob job) {
        IndexJob running = requireRunningReview(job);
        try {
            reviews.begin(running);
            IndexJob activeA = jobs.activateReviewTarget(running.id(), ReviewSide.A);
            SealedGeneration a = prepareSide(activeA, ReviewSide.A, sealedCapturedBaseline(activeA));
            IndexJob afterA = jobs.recordReviewSide(activeA.id(), ReviewSide.A, a);
            IndexJob activeB = jobs.activateReviewTarget(afterA.id(), ReviewSide.B);
            SealedGeneration b = prepareSide(activeB, ReviewSide.B, sameRevision(a, activeB) ? a : null);
            IndexJob afterB = jobs.recordReviewSide(activeB.id(), ReviewSide.B, b);
            reviewGitEvidence.prepare(afterB);
            IndexJob validating = jobs.beginReviewValidation(afterB.id());
            reviews.publishReady(validating);
            jobs.recordReviewReady(validating.id());
        } catch (ReviewPreparationException exception) {
            reviews.failUnpublished(running, exception.category());
            throw exception;
        } catch (RuntimeException exception) {
            reviews.failUnpublished(running, IndexFailureCategory.ANALYSIS_UNAVAILABLE);
            throw new ReviewPreparationException(IndexFailureCategory.ANALYSIS_UNAVAILABLE, "review preparation failed", exception);
        }
    }

    private SealedGeneration sealedCapturedBaseline(IndexJob job) {
        ReviewJobPayload payload = job.review().orElseThrow(() -> new IllegalStateException("review payload is required"));
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("repoId", job.repositoryId().value())
                .append("sourceRevision", payload.baseline().pointer().revision().value())
                .append("generationId", payload.baseline().pointer().generationId().value())
                .append("identityDigest", payload.baseline().pointer().manifestDigest().value())
                .append("writeState", "SEALED_VALID")).first();
        Document inputs = Objects.isNull(manifest) ? null : manifest.get("analysisInputs", Document.class);
        String storedFingerprint = Objects.isNull(manifest) ? null : manifest.getString("analysisFingerprint");
        Document evidence = Objects.isNull(manifest) ? null : manifest.get("analysisEvidence", Document.class);
        if (Objects.isNull(inputs) || Objects.isNull(storedFingerprint) || Objects.isNull(evidence)) {
            throw new ReviewPreparationException(IndexFailureCategory.REVIEW_EVIDENCE_MISMATCH,
                    "captured review baseline is no longer a complete sealed generation");
        }
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(template.getConverter().read(AnalysisInputs.class, inputs));
        SemanticAnalysisEvidence analysisEvidence = template.getConverter().read(SemanticAnalysisEvidence.class, evidence);
        if (!fingerprint.digest().equals(storedFingerprint) || !analysisEvidence.fingerprintDigest().equals(storedFingerprint)) {
            throw new ReviewPreparationException(IndexFailureCategory.REVIEW_EVIDENCE_MISMATCH,
                    "captured review baseline has inconsistent semantic analysis evidence");
        }
        return new SealedGeneration(new SelectedGeneration(job.repositoryId(), payload.baseline().pointer().revision(),
                payload.baseline().pointer().generationId(), payload.baseline().pointer().manifestDigest()),
                fingerprint, analysisEvidence);
    }

    private SealedGeneration prepareSide(IndexJob job, ReviewSide side, SealedGeneration preferredCandidate) {
        List<SealedGeneration> candidates = new ArrayList<>();
        if (Objects.nonNull(preferredCandidate)) {
            candidates.add(preferredCandidate);
        }
        for (SealedGeneration alternate : sealedCandidates(job)) {
            if (candidates.stream().noneMatch(candidate -> candidate.selected().equals(alternate.selected()))) {
                candidates.add(alternate);
            }
        }
        return endpointPreparation.prepare(job, side, candidates);
    }

    private static boolean sameRevision(SealedGeneration a, IndexJob activeB) {
        return a.selected().revision().equals(activeB.target().orElseThrow().revision());
    }

    private List<SealedGeneration> sealedCandidates(IndexJob job) {
        IndexJobTarget target = job.target().orElseThrow();
        List<SealedGeneration> candidates = new ArrayList<>();
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("repoId", job.repositoryId().value())
                .append("sourceRevision", target.revision().value()).append("writeState", "SEALED_VALID"))
                .forEach(manifest -> {
                    try {
                        candidates.add(sealedFromManifest(job.repositoryId(), target.revision(), manifest));
                    } catch (RuntimeException ignored) {
                        // Incomplete foreign generations are never reuse candidates.
                    }
                });
        return List.copyOf(candidates);
    }

    private SealedGeneration sealedFromManifest(RepositoryId repositoryId,
                                                RepositoryRevision revision, Document manifest) {
        Document inputs = manifest.get("analysisInputs", Document.class);
        String storedFingerprint = manifest.getString("analysisFingerprint");
        Document evidence = manifest.get("analysisEvidence", Document.class);
        if (Objects.isNull(inputs) || Objects.isNull(storedFingerprint) || Objects.isNull(evidence)) {
            throw new IllegalArgumentException("sealed generation has incomplete analysis evidence");
        }
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(template.getConverter().read(AnalysisInputs.class, inputs));
        SemanticAnalysisEvidence analysisEvidence = template.getConverter().read(SemanticAnalysisEvidence.class, evidence);
        if (!fingerprint.digest().equals(storedFingerprint) || !analysisEvidence.fingerprintDigest().equals(storedFingerprint)) {
            throw new IllegalArgumentException("sealed generation analysis evidence is inconsistent");
        }
        return new SealedGeneration(new SelectedGeneration(repositoryId, revision, new GenerationId(manifest.getString("generationId")),
                new ManifestDigest(manifest.getString("identityDigest"))), fingerprint, analysisEvidence);
    }

    private static IndexJob requireRunningReview(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW || !requiredJob.active()
                || requiredJob.phase() != IndexJobPhase.RUNNING
                || requiredJob.review().orElseThrow().stage() != ReviewPreparationStage.PREPARING_A) {
            throw new IllegalArgumentException("review preparation requires its active PREPARING_A owner");
        }
        return requiredJob;
    }
}
