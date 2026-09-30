package com.java.semantic.indexer.job;

import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.index.PublishedGenerationPointer;
import org.springframework.stereotype.Service;
import com.java.semantic.model.review.ReviewSelection;

import java.util.Objects;
import java.util.Optional;

/** Admits durable preparations and private maintenance; all analysis runs later in the worker. */
@Service
public final class IndexRequestService {
    private final RepositoryRevisionResolver revisionResolver;
    private final RepositoryRuntimeRegistry repositories;
    private final IndexJobStore jobs;

    public IndexRequestService(RepositoryRevisionResolver revisionResolver, RepositoryRuntimeRegistry repositories, IndexJobStore jobs) {
        this.revisionResolver = Objects.requireNonNull(revisionResolver, "revision resolver is required");
        this.repositories = Objects.requireNonNull(repositories, "repositories is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    }

    public IndexJob ensure(RepositoryId repositoryId) {
        jobs.reconcileCommitted(repositoryId);
        return jobs.admitEnsure(repositoryId, revisionResolver.ensure(repositoryId));
    }

    public IndexJob sync(RepositoryId repositoryId, Optional<String> branch) {
        return admit(repositoryId, revisionResolver.sync(repositoryId, branch), false);
    }

    public IndexJob checkout(RepositoryId repositoryId, String revision) {
        return admit(repositoryId, revisionResolver.checkout(repositoryId, revision), false);
    }

    public IndexJob rebuild(RepositoryId repositoryId, boolean authorizeIncompatibleSchema,
                            PublishedGenerationPointer expectedCurrent) {
        if (!authorizeIncompatibleSchema) {
            throw new IllegalArgumentException("incompatible schema rebuild requires explicit authorization");
        }
        Objects.requireNonNull(expectedCurrent, "expected current pointer is required");
        RepositoryRevision revision = jobs.currentRevision(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
        jobs.reconcileCommitted(repositoryId);
        return jobs.admitRebuild(repositoryId, revision, expectedCurrent);
    }

    public IndexJob getJob(RepositoryId repositoryId, Optional<IndexJobId> jobId,
                           Optional<PreparationRequestId> requestId) {
        repositories.get(repositoryId);
        Objects.requireNonNull(jobId, "job selector is required");
        Objects.requireNonNull(requestId, "request selector is required");
        if (jobId.isPresent() == requestId.isPresent()) {
            throw new IllegalArgumentException("exactly one jobId or requestId is required");
        }
        if (jobId.isPresent()) {
            return jobs.find(repositoryId, jobId.orElseThrow())
                    .orElseThrow(IndexJobNotFoundException::new);
        }
        return jobs.find(repositoryId, requestId.orElseThrow())
                .orElseThrow(PreparationRequestNotFoundException::new);
    }

    public Optional<PublishedGenerationPointer> currentPointer(RepositoryId repositoryId) {
        return jobs.publicationState(repositoryId).flatMap(IndexPublicationState::currentPointer);
    }

    public Optional<IndexPublicationState> publicationState(RepositoryId repositoryId) {
        return jobs.publicationState(repositoryId);
    }

    public IndexJob rollback(RepositoryId repositoryId, PublishedGenerationPointer expectedCurrent,
                             PublishedGenerationPointer expectedRollback) {
        jobs.reconcileCommitted(repositoryId);
        return jobs.admitRollback(repositoryId, expectedCurrent, expectedRollback);
    }

    public IndexJob refreshRepositoryMetadata(RepositoryId repositoryId, PreparationRequestId requestId,
                                              Optional<String> requestedBranch) {
        PreparationRequest request = PreparationRequest.metadata(requestId, requestedBranch);
        String effectiveBranch = requestedBranch.orElse(repositories.get(repositoryId).defaultBranch());
        rejectReused(repositoryId, requestId);
        jobs.reconcileCommitted(repositoryId);
        return jobs.admitMetadata(repositoryId, request, effectiveBranch);
    }

    public IndexJob prepareCodebase(RepositoryId repositoryId, PreparationRequestId requestId) {
        PreparationRequest request = PreparationRequest.codebase(requestId);
        String branch = repositories.get(repositoryId).defaultBranch();
        rejectReused(repositoryId, requestId);
        jobs.reconcileCommitted(repositoryId);
        RepositoryRevision revision = revisionResolver.sync(repositoryId, Optional.of("refs/heads/" + branch));
        return jobs.admitCodebase(repositoryId, request, branch, revision);
    }

    public IndexJob prepareReview(RepositoryId repositoryId, PreparationRequestId requestId, ReviewSelection selection) {
        PreparationRequest request = PreparationRequest.review(requestId, selection);
        repositories.get(repositoryId);
        rejectReused(repositoryId, requestId);
        jobs.reconcileCommitted(repositoryId);
        return jobs.admitReview(repositoryId, request);
    }

    private void rejectReused(RepositoryId repositoryId, PreparationRequestId requestId) {
        jobs.find(repositoryId, requestId).ifPresent(job -> {
            throw new PreparationRequestReusedException(job.id(), requestId);
        });
    }

    private IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild) {
        jobs.reconcileCommitted(repositoryId);
        return jobs.admit(repositoryId, revision, rebuild);
    }
}
