package com.java.semantic.indexer.job;

import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.time.Duration;
import java.util.Optional;

public interface IndexJobStore {
    IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild);
    IndexJob admitEnsure(RepositoryId repositoryId, RepositoryRevision revision);
    IndexJob admitRollback(RepositoryId repositoryId, PublishedGenerationPointer expectedCurrent,
                           PublishedGenerationPointer expectedRollback);
    Optional<IndexJob> find(IndexJobId jobId);
    Optional<IndexJob> claim(IndexJobId jobId, String workerId, Duration claimLifetime);
    boolean renew(IndexJob claimedJob, Duration claimLifetime);
    boolean revoke(IndexJob claimedJob);
    boolean failAfterRevocation(IndexJob claimedJob, IndexFailureCategory category);
    void failExpiredClaims();
    void reconcileCommittedJobs();
    void recoverRevokedClaims();
    void recoverRevokedClaims(RepositoryId repositoryId);
    Optional<IndexJob> reconcileCommitted(RepositoryId repositoryId);
    Optional<RepositoryRevision> currentRevision(RepositoryId repositoryId);
    Optional<RollbackGenerationCommand> rollbackCommand(IndexJob job);
    Optional<IndexPublicationIntent> prepareBuildPublication(IndexJob job, ManifestDigest sealedManifestDigest);
    Optional<IndexPublicationIntent> publicationIntent(IndexJobId jobId);
}
