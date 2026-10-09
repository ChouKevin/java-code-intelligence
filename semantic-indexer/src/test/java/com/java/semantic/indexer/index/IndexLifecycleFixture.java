package com.java.semantic.indexer.index;

import com.java.semantic.indexer.application.PreparationCoordinator;
import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.indexer.job.IndexJobDispatcher;
import com.java.semantic.indexer.job.IndexJobExecutor;
import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.RepositorySourceManager;
import com.java.semantic.indexer.source.SourceGarbageCollector;
import com.java.semantic.indexer.source.SourcePreparationService;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.indexer.source.SourceRetentionStore;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.time.Clock;
import tools.jackson.databind.ObjectMapper;

/** Real optional-index components sharing a source fixture's writer and admission authority. */
public final class IndexLifecycleFixture {
    public final FileSemanticIndexJobStore jobs;
    public final IndexPublicationStore publications;
    public final IndexRetentionStore retention;
    public final IndexGarbageCollector collector;
    public final SemanticIndexManager manager;
    public final PreparationCoordinator coordinator;
    private final FileSourceJobStore sourceJobs;
    private final SourcePublicationStore sources;

    public IndexLifecycleFixture(RepositoryProperties properties, ObjectMapper mapper, DurableSourceFiles ownership,
            FileSourceJobStore sourceJobs, SourcePublicationStore sources, Clock clock) throws IOException {
        this.sourceJobs = sourceJobs;
        this.sources = sources;
        RepositoryRegistry registry = new RepositoryRegistry(properties);
        IndexConfiguration configuration = new IndexConfiguration();
        jobs = new FileSemanticIndexJobStore(properties, mapper, ownership);
        publications = configuration.indexPublications(properties, mapper, ownership, sources, registry);
        IndexSourceAccess access = new IndexSourceAccess(sources, registry, properties);
        manager = new SemanticIndexManager(jobs, access, new SqliteIndexBuilder(mapper, new JavaSyntaxExtractor()), publications, properties);
        retention = new IndexRetentionStore(properties, mapper, ownership, registry);
        collector = new IndexGarbageCollector(properties, registry, sources, new SourceRetentionStore(properties, mapper, registry),
                publications, retention, mapper, clock);
        coordinator = new PreparationCoordinator(sourceJobs, jobs, new SourcePreparationService(sourceJobs, sources, registry),
                access, publications, registry);
    }

    public IndexJobDispatcher dispatcher(RepositorySourceManager sourceManager, SourceGarbageCollector sourceCollector) {
        return new IndexJobDispatcher(sourceJobs, new IndexJobExecutor(sourceManager), sources, sourceCollector,
                jobs, manager, collector, coordinator);
    }
}
