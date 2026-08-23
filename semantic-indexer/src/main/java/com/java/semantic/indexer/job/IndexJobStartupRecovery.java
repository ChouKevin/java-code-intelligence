package com.java.semantic.indexer.job;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Performs idempotent claim expiry, committed-pointer reconciliation, and revoked-claim recovery before serving work. */
@Component
@ConditionalOnProperty(
        prefix = "semantic.index-jobs.startup-recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class IndexJobStartupRecovery implements ApplicationRunner {
    private final IndexJobWorker worker;
    private final IndexJobStore jobs;

    public IndexJobStartupRecovery(IndexJobWorker worker, IndexJobStore jobs) {
        this.worker = Objects.requireNonNull(worker, "worker is required");
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        worker.expireClaims();
        jobs.reconcileCommittedJobs();
        jobs.recoverRevokedClaims();
    }
}
