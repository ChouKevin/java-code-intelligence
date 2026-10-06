package com.java.semantic.indexer.source;

import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.job.FileSourceJobStore;
import com.java.semantic.repository.config.RepositoryProperties;
import tools.jackson.databind.ObjectMapper;

/** Native fixtures use the same registry-first initialization as application startup. */
public final class SourceLifecycleFixture {
    private SourceLifecycleFixture() {}

    public static SourcePublicationStore publications(RepositoryProperties properties, ObjectMapper mapper,
            DurableSourceFiles owner, FileSourceJobStore jobs) {
        RepositoryRegistry registry = new RepositoryRegistry(properties);
        SourcePublicationStore publications = new SourcePublicationStore(properties, mapper, owner, jobs,
                new SourceRetentionStore(properties, mapper, registry));
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(properties.getSourcePublishedRoot()).resolve("repositories.json"))) {
            new ConfiguredRepositoryPublisher(registry, properties, mapper, owner, publications).publish();
        }
        return publications;
    }

    public static ConfiguredRepositoryPublisher publisher(RepositoryRegistry registry, RepositoryProperties properties,
            ObjectMapper mapper, DurableSourceFiles owner) {
        return new ConfiguredRepositoryPublisher(registry, properties, mapper, owner,
                new SourcePublicationStore(properties, mapper, owner, new FileSourceJobStore(properties, mapper, owner),
                        new SourceRetentionStore(properties, mapper, registry)));
    }

    public static SourceGarbageCollector collector(RepositoryProperties properties, ObjectMapper mapper,
            SourcePublicationStore publications) {
        RepositoryRegistry registry = new RepositoryRegistry(properties);
        return new SourceGarbageCollector(properties, registry, publications,
                new SourceRetentionStore(properties, mapper, registry), java.time.Clock.systemUTC());
    }
}
