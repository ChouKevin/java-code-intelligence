package com.java.semantic.indexer.job;

import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.source.SourcePublicationStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Reconciles only committed publication proofs; never replays interrupted RUNNING work. */
@Component
public final class IndexJobStartupRecovery implements ApplicationRunner {
    private final FileSourceJobStore jobs;
    private final SourcePublicationStore publications;
    private final ConfiguredRepositoryPublisher registry;

    public IndexJobStartupRecovery(FileSourceJobStore jobs, SourcePublicationStore publications,
            ConfiguredRepositoryPublisher registry) {
        this.jobs = jobs;
        this.publications = publications;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        registry.publish();
        jobs.recover(publications);
    }
}
