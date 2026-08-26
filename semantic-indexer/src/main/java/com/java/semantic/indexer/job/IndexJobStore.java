package com.java.semantic.indexer.job;

import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Optional;

public interface IndexJobStore {
    IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild);
    IndexJob admitRebuild(RepositoryId repositoryId, RepositoryRevision revision,
                          PublishedGenerationPointer expectedCurrent);
    IndexJob admitEnsure(RepositoryId repositoryId, RepositoryRevision revision);
    IndexJob admitRollback(RepositoryId repositoryId, PublishedGenerationPointer expectedCurrent,
                           PublishedGenerationPointer expectedRollback);
    Optional<IndexJob> find(IndexJobId jobId);
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
}
