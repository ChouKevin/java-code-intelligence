package com.java.semantic.indexer.job;

import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.model.review.ResolvedReviewEndpoints;

import java.util.Optional;

public interface IndexJobStore {
    IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild);
    IndexJob admitRebuild(RepositoryId repositoryId, RepositoryRevision revision,
                          PublishedGenerationPointer expectedCurrent);
    IndexJob admitEnsure(RepositoryId repositoryId, RepositoryRevision revision);
    IndexJob admitRollback(RepositoryId repositoryId, PublishedGenerationPointer expectedCurrent,
                           PublishedGenerationPointer expectedRollback);
    IndexJob admitReset(RepositoryId repositoryId);
    IndexJob admitMetadata(RepositoryId repositoryId, PreparationRequest request, String effectiveBranch);
    IndexJob admitCodebase(RepositoryId repositoryId, PreparationRequest request, String branch, RepositoryRevision revision);
    IndexJob admitReview(RepositoryId repositoryId, PreparationRequest request);
    IndexJob resolveReviewEndpoints(IndexJobId jobId, ResolvedReviewEndpoints endpoints);
    IndexJob activateReviewTarget(IndexJobId jobId, ReviewSide side);
    IndexJob recordReviewSide(IndexJobId jobId, ReviewSide side, SealedGeneration generation);
    IndexJob beginReviewValidation(IndexJobId jobId);
    IndexJob recordReviewReady(IndexJobId jobId);
    Optional<IndexJob> find(IndexJobId jobId);
    Optional<IndexJob> find(RepositoryId repositoryId, IndexJobId jobId);
    Optional<IndexJob> find(RepositoryId repositoryId, PreparationRequestId requestId);
    Optional<IndexJob> startNextAccepted();
    boolean complete(IndexJobId jobId);
    boolean fail(IndexJobId jobId, IndexFailureCategory category);
    void reconcileCommittedJobs();
    void failUnreconciledRunningJobs();
    Optional<IndexJob> reconcileCommitted(RepositoryId repositoryId);
    Optional<RepositoryRevision> currentRevision(RepositoryId repositoryId);
    Optional<IndexPublicationState> publicationState(RepositoryId repositoryId);
    Optional<RollbackGenerationCommand> rollbackCommand(IndexJob job);
    Optional<IndexPublicationIntent> prepareBuildPublication(IndexJob job, ManifestDigest sealedManifestDigest);
    boolean reviewReady(IndexJob job);
    boolean gitEvidenceReady(IndexJob job);
}
