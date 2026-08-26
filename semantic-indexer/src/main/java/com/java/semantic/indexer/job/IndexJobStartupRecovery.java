package com.java.semantic.indexer.job;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reconciles committed targets, then closes interrupted running jobs before serving work. */
@Component
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
