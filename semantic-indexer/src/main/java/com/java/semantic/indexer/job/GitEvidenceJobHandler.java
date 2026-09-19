package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.git.GitCatalogManifest;
import com.java.semantic.model.git.GitHistoryManifest;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.java.semantic.repository.port.GitRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/** Runs Git evidence preparation inside the existing serialized dispatcher. */
@Component
public final class GitEvidenceJobHandler {
    private final RepositoryRuntimeRegistry repositories;
    private final GitRepositoryPort git;
    private final GitEvidencePublicationStore evidence;

    public GitEvidenceJobHandler(RepositoryRuntimeRegistry repositories, GitRepositoryPort git, GitEvidencePublicationStore evidence) {
        this.repositories = Objects.requireNonNull(repositories, "repositories are required");
        this.git = Objects.requireNonNull(git, "git repository port is required");
        this.evidence = Objects.requireNonNull(evidence, "git evidence store is required");
    }

    public void prepare(IndexJob job) {
        RepositoryRuntime runtime = repositories.get(job.repositoryId());
        runtime.lock().writeLock().lock();
        try {
            evidence.verifySchemaBeforeEvidence();
            try {
                switch (job.operation()) {
                    case GIT_REFS -> refs(job, runtime);
                    case GIT_HISTORY -> history(job, runtime);
                    case GIT_COMPARISON -> comparison(job, runtime);
                    default -> throw new IllegalArgumentException("not a Git evidence job");
                }
            } catch (RuntimeException exception) {
                evidence.fail(job);
                throw exception;
            }
        } finally {
            runtime.lock().writeLock().unlock();
        }
    }

    public void prepareReview(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        if (requiredJob.operation() != IndexJobOperation.REVIEW) {
            throw new IllegalArgumentException("review Git evidence requires a REVIEW job");
        }
        ReviewJobPayload payload = requiredJob.review().orElseThrow(() -> new IllegalArgumentException("review payload is required"));
        RepositoryRuntime runtime = repositories.get(requiredJob.repositoryId());
        runtime.lock().writeLock().lock();
        try {
            if (!git.isCloned(runtime.workingTree())) {
                git.clone(runtime.workingTree(), runtime.remoteUrl());
            }
            git.fetch(runtime.workingTree());
            RepositoryRevision previous = payload.baseline().pointer().revision();
            RepositoryRevision current = payload.requestedRevision();
            git.verifyComparisonEndpoints(runtime.workingTree(), previous, current);
            GitPreparedComparison comparison = git.prepareComparison(runtime.workingTree(), previous, current);
            evidence.publishComparison(requiredJob, comparison, Instant.now(),
                    new GitEvidenceOwnership(GitPublicationScope.REVIEW, java.util.Optional.of(payload.reviewId())));
        } catch (RuntimeException exception) {
            evidence.fail(requiredJob);
            throw exception;
        } finally {
            runtime.lock().writeLock().unlock();
        }
    }

    private void refs(IndexJob job, RepositoryRuntime runtime) {
        if (!git.isCloned(runtime.workingTree())) {
            git.clone(runtime.workingTree(), runtime.remoteUrl());
        }
        GitCatalogManifest manifest = evidence.beginCatalog(job, Instant.now());
        evidence.appendBranches(manifest, git.fetchRemoteBranches(runtime.workingTree()));
    }

    private void history(IndexJob job, RepositoryRuntime runtime) {
        GitEvidenceJob payload = job.gitEvidence().orElseThrow(() -> new IllegalStateException("Git history payload is required"));
        String branch = payload.branch().orElseThrow(() -> new IllegalArgumentException("Git history branch is required"));
        RepositoryRevision revision = payload.revision().orElseThrow(() -> new IllegalArgumentException("Git history revision is required"));
        com.java.semantic.model.git.GitEvidenceId catalogId = payload.catalogId().orElseThrow(() -> new IllegalArgumentException("Git history catalog is required"));
        if (!evidence.catalogContainsHead(job.repositoryId(), catalogId, branch, revision)) {
            throw new IllegalArgumentException("Git history catalog, branch and revision do not match");
        }
        GitHistoryManifest manifest = evidence.beginHistory(job, catalogId, branch, revision, Instant.now());
        // JGit invokes this callback serially while the one dispatcher worker owns the repository lock.
        java.util.concurrent.atomic.AtomicLong ordinal = new java.util.concurrent.atomic.AtomicLong();
        git.streamReachableHistory(runtime.workingTree(), revision, commit -> {
            evidence.appendCommit(manifest, ordinal.getAndIncrement(), commit);
        });
        evidence.readyHistory(manifest, ordinal.get());
    }

    private void comparison(IndexJob job, RepositoryRuntime runtime) {
        GitEvidenceJob payload = job.gitEvidence().orElseThrow(() -> new IllegalStateException("Git comparison payload is required"));
        RepositoryRevision previous = payload.previousRevision().orElseThrow(() -> new IllegalArgumentException("Git comparison previous is required"));
        RepositoryRevision current = payload.revision().orElseThrow(() -> new IllegalArgumentException("Git comparison current is required"));
        if (!git.isCloned(runtime.workingTree())) {
            git.clone(runtime.workingTree(), runtime.remoteUrl());
        }
        git.fetch(runtime.workingTree());
        git.verifyComparisonEndpoints(runtime.workingTree(), previous, current);
        GitPreparedComparison comparison = git.prepareComparison(runtime.workingTree(), previous, current);
        evidence.publishComparison(job, comparison, Instant.now(), GitEvidenceOwnership.standalone());
    }
}
