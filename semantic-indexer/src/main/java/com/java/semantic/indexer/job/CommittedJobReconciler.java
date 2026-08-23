package com.java.semantic.indexer.job;

import com.java.semantic.model.repository.RepositoryId;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/** Closes only the job proven by the repository's committed pointer, never an arbitrary active job. */
@Component
public final class CommittedJobReconciler {
    private final IndexJobStore jobs;

    public CommittedJobReconciler(IndexJobStore jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    }

    public Optional<IndexJob> reconcile(RepositoryId repositoryId) {
        return jobs.reconcileCommitted(repositoryId);
    }
}
