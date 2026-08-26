package com.java.semantic.indexer.job;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reconciles committed targets, then closes interrupted running jobs before serving work. */
@Component
@ConditionalOnProperty(
        prefix = "semantic.index-jobs.startup-recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class IndexJobStartupRecovery implements ApplicationRunner {
    private final IndexJobStore jobs;

    public IndexJobStartupRecovery(IndexJobStore jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        jobs.reconcileCommittedJobs();
        jobs.failUnreconciledRunningJobs();
    }
}
