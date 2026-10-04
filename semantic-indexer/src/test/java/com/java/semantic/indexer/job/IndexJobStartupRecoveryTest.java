package com.java.semantic.indexer.job;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.indexer.source.RepositoryRegistry;
import com.java.semantic.indexer.source.SourcePublicationStore;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class IndexJobStartupRecoveryTest {
    @TempDir Path root;

    @Test
    void restart_leaves_accepted_work_available_but_fails_unpublished_running_without_refetch() throws Exception {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setSourceAdminRoot(root.resolve("source-admin").toString());
        properties.setSourcePublishedRoot(root.resolve("source-published").toString());
        RepositoryProperties.RepositoryConfig orders = new RepositoryProperties.RepositoryConfig();
        orders.setUrl(root.resolve("orders-git").toUri().toString());
        orders.setDisplayName("Orders");
        RepositoryProperties.RepositoryConfig other = new RepositoryProperties.RepositoryConfig();
        other.setUrl(root.resolve("other-git").toUri().toString());
        other.setDisplayName("Other");
        properties.setRepositories(java.util.Map.of("orders", orders, "other", other));
        RepositoryId repository = new RepositoryId("orders");
        PreparationRequestId runningRequest = new PreparationRequestId(UUID.randomUUID().toString());
        PreparationRequestId acceptedRequest = new PreparationRequestId(UUID.randomUUID().toString());
        try (DurableSourceFiles writer = new DurableSourceFiles(root.resolve("source-admin"))) {
            RepositoryRegistry registry = new RepositoryRegistry(properties);
            registry.bindAll();
            FileSourceJobStore jobs = new FileSourceJobStore(properties, JsonMapper.builder().build(), writer);
            jobs.admit(repository, runningRequest, Optional.empty(), "main", registry.origin(repository), Optional.empty());
            jobs.claimNext().orElseThrow();
            RepositoryId second = new RepositoryId("other");
            jobs.admit(second, acceptedRequest, Optional.empty(), "main", registry.origin(second), Optional.empty());
        }
        try (DurableSourceFiles writer = new DurableSourceFiles(root.resolve("source-admin"))) {
            FileSourceJobStore jobs = new FileSourceJobStore(properties, JsonMapper.builder().build(), writer);
            jobs.recover(new SourcePublicationStore(properties, JsonMapper.builder().build(), writer, jobs));
            assertThat(jobs.find(repository, runningRequest).orElseThrow().phase()).isEqualTo(SourcePreparationJob.Phase.FAILED);
            assertThat(jobs.find(repository, runningRequest).orElseThrow().failureCode()).contains("WORKER_INTERRUPTED");
            assertThat(jobs.claimNext().orElseThrow().requestId()).isEqualTo(acceptedRequest.value());
        }
    }
}
