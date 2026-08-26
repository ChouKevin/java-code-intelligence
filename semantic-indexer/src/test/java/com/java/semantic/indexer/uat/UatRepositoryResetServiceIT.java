package com.java.semantic.indexer.uat;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobAlreadyActiveException;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.config.RepositoryProperties;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class UatRepositoryResetServiceIT {
    @Test
    void cleanup_deletes_only_the_requested_repository_and_can_be_repeated() throws Exception {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            try (MongoClient client = MongoClients.create(container.getConnectionString())) {
                MongoTemplate template = new MongoTemplate(client, "semantic_uat");
                new IndexSchemaBootstrap(template).bootstrap();
                Path repositories = Files.createTempDirectory("uat-repositories");
                Path workspaces = Files.createTempDirectory("uat-workspaces");
                Files.createDirectories(repositories.resolve("payment"));
                Files.createDirectories(repositories.resolve("order"));
                Files.createDirectories(workspaces.resolve("payment"));
                Files.createDirectories(workspaces.resolve("order"));
                seedRepositoryData(template, "payment");
                seedRepositoryData(template, "order");
                seedOldJob(template, "payment");
                seedOldJob(template, "order");
                template.getCollection(IndexCollections.SOURCE_ARTIFACTS).insertOne(new Document("sourceArtifactId", "shared-artifact"));
                MongoIndexJobStore jobs = new MongoIndexJobStore(template);
                UatRepositoryResetService resets = new UatRepositoryResetService(template, jobs, properties(repositories),
                        new JdtLsProperties(true, Path.of("/opt/jdtls"), workspaces, Duration.ofSeconds(1), Duration.ofSeconds(1),
                                Duration.ofSeconds(1), 1, Duration.ofMinutes(1), Duration.ofMinutes(1), "1g"));

                IndexJob accepted = resets.admit(RepositoryId.of("payment"));
                assertThatThrownBy(() -> resets.admit(RepositoryId.of("payment")))
                        .isInstanceOf(IndexJobAlreadyActiveException.class);
                IndexJob running = jobs.startNextAccepted().orElseThrow();
                simulateInterruptedCleanup(template, repositories, workspaces);
                resets.reset(running);
                resets.reset(running);

                assertThat(accepted.operation()).isEqualTo(IndexJobOperation.RESET);
                assertThat(accepted.phase()).isEqualTo(IndexJobPhase.ACCEPTED);
                assertThat(template.getCollection(IndexCollections.REPOSITORIES).countDocuments(new Document("repoId", "payment"))).isZero();
                assertThat(template.getCollection(IndexCollections.REPOSITORIES).countDocuments(new Document("repoId", "order"))).isOne();
                assertThat(template.getCollection(IndexCollections.INDEX_JOBS).countDocuments(new Document("jobId", running.id().value()))).isOne();
                assertThat(template.getCollection(IndexCollections.INDEX_JOBS).countDocuments(new Document("jobId", "payment-old"))).isZero();
                assertThat(template.getCollection(IndexCollections.INDEX_JOBS).countDocuments(new Document("jobId", "order-old"))).isOne();
                assertThat(template.getCollection(IndexCollections.SOURCE_ARTIFACTS).countDocuments()).isOne();
                assertThat(repositories.resolve("payment")).doesNotExist();
                assertThat(workspaces.resolve("payment")).doesNotExist();
                assertThat(repositories.resolve("order")).exists();
                assertThat(workspaces.resolve("order")).exists();
                for (String collection : repositoryCollections()) {
                    assertThat(template.getCollection(collection).countDocuments(new Document("repoId", "payment"))).isZero();
                    assertThat(template.getCollection(collection).countDocuments(new Document("repoId", "order"))).isOne();
                }

                assertThatThrownBy(() -> resets.admit(RepositoryId.of("missing")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not configured");
                MongoTemplate wrongDatabase = new MongoTemplate(client, "semantic");
                UatRepositoryResetService unsafeDatabase = new UatRepositoryResetService(wrongDatabase,
                        new MongoIndexJobStore(wrongDatabase), properties(repositories),
                        new JdtLsProperties(true, Path.of("/opt/jdtls"), workspaces, Duration.ofSeconds(1),
                                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, Duration.ofMinutes(1),
                                Duration.ofMinutes(1), "1g"));
                assertThatThrownBy(() -> unsafeDatabase.admit(RepositoryId.of("payment")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("semantic_uat");
            }
        }
    }

    private static void seedOldJob(MongoTemplate template, String repositoryId) {
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", repositoryId + "-old")
                .append("repoId", repositoryId).append("active", false).append("phase", "COMPLETE")
                .append("operation", "BUILD"));
    }

    private static void simulateInterruptedCleanup(MongoTemplate template, Path repositories, Path workspaces) throws Exception {
        template.getCollection(IndexCollections.REPOSITORIES).deleteOne(new Document("repoId", "payment"));
        template.getCollection(IndexCollections.SYMBOLS).deleteMany(new Document("repoId", "payment"));
        Files.delete(repositories.resolve("payment"));
        Files.delete(workspaces.resolve("payment"));
    }

    private static RepositoryProperties properties(Path root) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(root.toString());
        Map<String, RepositoryProperties.RepositoryConfig> repositories = new LinkedHashMap<>();
        repositories.put("payment", new RepositoryProperties.RepositoryConfig());
        repositories.put("order", new RepositoryProperties.RepositoryConfig());
        properties.setRepositories(repositories);
        return properties;
    }

    private static void seedRepositoryData(MongoTemplate template, String repositoryId) {
        template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", repositoryId));
        for (String collection : repositoryCollections()) {
            template.getCollection(collection).insertOne(new Document("repoId", repositoryId));
        }
    }

    private static java.util.List<String> repositoryCollections() {
        return java.util.List.of(IndexCollections.GENERATION_MANIFESTS, IndexCollections.GENERATION_FILES, IndexCollections.SYMBOLS,
                IndexCollections.RELATIONS, IndexCollections.ENTRY_POINTS, IndexCollections.SEARCH);
    }
}
