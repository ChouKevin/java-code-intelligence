package com.java.semantic.indexer.job;

import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.index.PublishedGenerationPointer;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Accepts administrative requests after resolving repository revisions; indexing itself is deliberately deferred to a worker. */
@Service
public final class IndexRequestService {
    private final RepositoryRevisionResolver revisionResolver;
    private final IndexJobStore jobs;

    public IndexRequestService(RepositoryRevisionResolver revisionResolver, IndexJobStore jobs) {
        this.revisionResolver = Objects.requireNonNull(revisionResolver, "revision resolver is required");
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

    public Optional<IndexJob> job(IndexJobId jobId) {
        return jobs.find(jobId);
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

    private IndexJob admit(RepositoryId repositoryId, RepositoryRevision revision, boolean rebuild) {
        jobs.reconcileCommitted(repositoryId);
        return jobs.admit(repositoryId, revision, rebuild);
    }
}
