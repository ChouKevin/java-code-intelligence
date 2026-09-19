package com.java.semantic.indexer.review;

import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.job.GitEvidenceJobHandler;
import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.review.ReviewSide;
import java.util.Objects;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Serially prepares the two admitted immutable review endpoints, then one review-owned Git comparison. */
@Service
public final class ReviewPreparationService {
    private final IndexJobStore jobs;
    private final RepositoryBuildRunner buildRunner;
    private final GitEvidenceJobHandler gitEvidence;
    private final ReviewPublicationStore reviews;
    private final MongoTemplate template;

    public ReviewPreparationService(IndexJobStore jobs, RepositoryBuildRunner buildRunner, GitEvidenceJobHandler gitEvidence,
                                    ReviewPublicationStore reviews, MongoTemplate template) {
        this.jobs = Objects.requireNonNull(jobs, "job store is required");
        this.buildRunner = Objects.requireNonNull(buildRunner, "build runner is required");
        this.gitEvidence = Objects.requireNonNull(gitEvidence, "Git evidence handler is required");
        this.reviews = Objects.requireNonNull(reviews, "review publication store is required");
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public void prepare(IndexJob job) {
        IndexJob running = requireRunningReview(job);
        try {
            reviews.begin(running);
            IndexJob activeA = jobs.activateReviewTarget(running.id(), ReviewSide.A);
            SealedGeneration a = sealedCapturedBaseline(activeA);
            IndexJob afterA = jobs.recordReviewSide(activeA.id(), ReviewSide.A, a);
            IndexJob activeB = jobs.activateReviewTarget(afterA.id(), ReviewSide.B);
            SealedGeneration b = sameRevision(a, activeB) ? a : buildRunner.seal(activeB);
            IndexJob afterB = jobs.recordReviewSide(activeB.id(), ReviewSide.B, b);
            gitEvidence.prepareReview(afterB);
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
        if (Objects.isNull(manifest) || Objects.isNull(manifest.get("analysisFingerprint", Document.class))
                || Objects.isNull(manifest.get("analysisEvidence", Document.class))) {
            throw new ReviewPreparationException(IndexFailureCategory.REVIEW_EVIDENCE_MISMATCH,
                    "captured review baseline is no longer a complete sealed generation");
        }
        return new SealedGeneration(new SelectedGeneration(job.repositoryId(), payload.baseline().pointer().revision(),
                payload.baseline().pointer().generationId(), payload.baseline().pointer().manifestDigest()),
                template.getConverter().read(AnalysisFingerprint.class, manifest.get("analysisFingerprint", Document.class)),
                template.getConverter().read(SemanticAnalysisEvidence.class, manifest.get("analysisEvidence", Document.class)));
    }

    private static boolean sameRevision(SealedGeneration a, IndexJob activeB) {
        return a.selected().revision().equals(activeB.target().orElseThrow().revision());
    }

    private static IndexJob requireRunningReview(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW || !requiredJob.active()
                || requiredJob.phase() != com.java.semantic.indexer.job.IndexJobPhase.RUNNING
                || requiredJob.review().orElseThrow().stage() != ReviewPreparationStage.PREPARING_A) {
            throw new IllegalArgumentException("review preparation requires its active PREPARING_A owner");
        }
        return requiredJob;
    }
}
