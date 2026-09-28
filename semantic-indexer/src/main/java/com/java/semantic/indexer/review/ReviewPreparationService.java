package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobStore;
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
import com.java.semantic.model.review.ResolvedReviewEndpoints;
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
            ResolvedReviewEndpoints resolved = reviewGitEvidence.resolve(running);
            IndexJob fixed = jobs.resolveReviewEndpoints(running.id(), resolved);
            SealedGeneration before = null;
            if (resolved.beforeRevision().isPresent()) {
                IndexJob activeBefore = jobs.activateReviewTarget(fixed.id(), ReviewSide.BEFORE);
                before = prepareSide(activeBefore, ReviewSide.BEFORE, null);
                fixed = jobs.recordReviewSide(activeBefore.id(), ReviewSide.BEFORE, before);
            }
            IndexJob activeAfter = jobs.activateReviewTarget(fixed.id(), ReviewSide.AFTER);
            SealedGeneration after = prepareSide(activeAfter, ReviewSide.AFTER,
                    Objects.nonNull(before) && sameRevision(before, activeAfter) ? before : null);
            IndexJob prepared = jobs.recordReviewSide(activeAfter.id(), ReviewSide.AFTER, after);
            reviewGitEvidence.prepare(prepared);
            IndexJob validating = jobs.beginReviewValidation(prepared.id());
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
                || requiredJob.review().orElseThrow().stage() != ReviewPreparationStage.RESOLVING) {
            throw new IllegalArgumentException("review preparation requires its active RESOLVING owner");
        }
        return requiredJob;
    }
}
