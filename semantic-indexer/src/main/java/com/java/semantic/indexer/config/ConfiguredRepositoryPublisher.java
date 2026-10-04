package com.java.semantic.indexer.config;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Publishes a bounded JSON array of sanitized descriptors; never Git credentials or private paths. */
@Component
public final class ConfiguredRepositoryPublisher {
    private final RepositoryRegistry registry;
    private final ObjectMapper mapper;
    private final Path path;
    private final DurableSourceFiles ownership;

    public ConfiguredRepositoryPublisher(RepositoryRegistry registry, RepositoryProperties properties,
            ObjectMapper mapper, DurableSourceFiles ownership) {
        this.registry = registry;
        this.mapper = mapper;
        this.ownership = ownership;
        path = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize().resolve("repositories.json");
    }

    public void publish() {
        List<SourceRepositoryDescriptor> descriptors = registry.descriptors();
        try {
            DurableSourceFiles.ensureDirectories(path.getParent(), path.getParent(),
                    DurableSourceFiles.Visibility.PUBLISHED);
            DurableSourceFiles.atomicBytes(path, mapper.writeValueAsBytes(descriptors), 1024 * 1024,
                    DurableSourceFiles.Visibility.PUBLISHED);
        } catch (IOException exception) {
            throw new IllegalStateException("repository registry cannot be published", exception);
        }
    }
}
