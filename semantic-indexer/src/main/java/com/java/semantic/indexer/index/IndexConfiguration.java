package com.java.semantic.indexer.index;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.indexer.source.SourceRetentionStore;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Filesystem bootstrap only; JDBC/native initialization remains inside each actual index operation. */
@Configuration
public class IndexConfiguration {
    @Bean
    public PlatformIdentity platformIdentity() { return new PlatformIdentity(); }

    @Bean
    public ResolutionInputStore resolutionInputs(RepositoryProperties properties, ObjectMapper mapper,
            PlatformIdentity platform, RepositoryRegistry registry) {
        return new ResolutionInputStore(properties, mapper, platform, registry);
    }

    @Bean
    public IndexBuildExecutor indexBuildExecutor(ObjectMapper mapper, ResolutionInputStore inputs,
            PlatformIdentity platform, RepositoryProperties properties) {
        return new IndexBuildExecutor(mapper, inputs, platform, properties);
    }

    @Bean
    public FileSemanticIndexJobStore semanticIndexJobs(RepositoryProperties properties, ObjectMapper mapper, DurableSourceFiles ownership) {
        return new FileSemanticIndexJobStore(properties, mapper, ownership);
    }

    @Bean
    public IndexSourceAccess indexSourceAccess(SourcePublicationStore sources, RepositoryRegistry registry, RepositoryProperties properties) {
        return new IndexSourceAccess(sources, registry, properties);
    }

    @Bean
    public IndexPublicationStore indexPublications(RepositoryProperties properties, ObjectMapper mapper,
            DurableSourceFiles ownership, SourcePublicationStore sources, RepositoryRegistry registry) throws IOException {
        Path admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        Path source = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
        Path configured = Path.of(properties.getIndexPublishedRoot());
        if (!configured.isAbsolute()) throw new IOException("index root must be absolute");
        Path index = configured.normalize();
        if (index.startsWith(admin) || admin.startsWith(index) || index.startsWith(source) || source.startsWith(index))
            throw new IOException("index, source and private roots must be separate");
        IndexFiles.safe(admin);
        IndexFiles.safe(source);
        IndexFiles.safe(index);
        DurableSourceFiles.ensureDirectories(index, index, DurableSourceFiles.Visibility.PUBLISHED);
        Path staging = admin.resolve("index/staging");
        DurableSourceFiles.ensureDirectories(admin, staging, DurableSourceFiles.Visibility.PRIVATE);
        if (!Files.getFileStore(staging).equals(Files.getFileStore(index)))
            throw new IOException("index staging and publication require one filesystem");
        return new IndexPublicationStore(properties, mapper, ownership, sources, registry);
    }

    @Bean
    public SemanticIndexManager semanticIndexManager(FileSemanticIndexJobStore jobs, IndexSourceAccess sources,
            IndexBuildExecutor builder, IndexPublicationStore publications, RepositoryProperties properties,
            ResolutionInputStore inputs) {
        return new SemanticIndexManager(jobs, sources, builder, publications, properties, inputs);
    }

    @Bean
    public IndexRetentionStore indexRetention(RepositoryProperties properties, ObjectMapper mapper,
            DurableSourceFiles ownership, RepositoryRegistry registry) {
        return new IndexRetentionStore(properties, mapper, ownership, registry);
    }

    @Bean
    public IndexGarbageCollector indexGarbageCollector(RepositoryProperties properties, RepositoryRegistry registry,
            SourcePublicationStore sources, SourceRetentionStore sourceRetention, IndexPublicationStore publications,
            IndexRetentionStore retention, ObjectMapper mapper, Clock clock) {
        return new IndexGarbageCollector(properties, registry, sources, sourceRetention, publications, retention, mapper, clock);
    }
}
