package com.java.semantic.indexer.job;

import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Reconciles committed targets, then closes interrupted running jobs before serving work. */
@Component
public final class IndexJobStartupRecovery implements ApplicationRunner {
    private final IndexJobStore jobs;
    private final ConfiguredRepositoryPublisher repositories;

    public IndexJobStartupRecovery(IndexJobStore jobs, ConfiguredRepositoryPublisher repositories) {
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.repositories = Objects.requireNonNull(repositories, "configured repository publisher is required");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        repositories.publish();
        jobs.reconcileCommittedJobs();
        jobs.failUnreconciledRunningJobs();
    }
}
