package com.java.semantic.indexer.job;

import com.java.semantic.indexer.application.PreparationCoordinator;
import com.java.semantic.indexer.index.FileSemanticIndexJobStore;
import com.java.semantic.indexer.index.IndexPublicationStore;
import com.java.semantic.indexer.index.SemanticIndexManager;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
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
    private final PreparationCoordinator coordinator;
    private final FileSemanticIndexJobStore indexJobs;
    private final IndexPublicationStore indexes;
    private final SemanticIndexManager indexManager;
    private final RepositoryRegistry repositories;

    public IndexJobStartupRecovery(FileSourceJobStore jobs, SourcePublicationStore publications,
            ConfiguredRepositoryPublisher registry, PreparationCoordinator coordinator, FileSemanticIndexJobStore indexJobs,
            IndexPublicationStore indexes, SemanticIndexManager indexManager, RepositoryRegistry repositories) {
        this.jobs = jobs;
        this.publications = publications;
        this.registry = registry;
        this.coordinator = coordinator;
        this.indexJobs = indexJobs;
        this.indexes = indexes;
        this.indexManager = indexManager;
        this.repositories = repositories;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        synchronized (jobs) {
            registry.publish();
            coordinator.validateStartup();
            jobs.recover(publications);
            indexJobs.recover(indexes);
            for (SourceRepositoryDescriptor descriptor : repositories.descriptors()) {
                indexJobs.visit(new RepositoryId(descriptor.repositoryId()), indexManager::cleanupFailedStage);
            }
        }
    }
}
