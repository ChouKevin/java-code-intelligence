package com.java.semantic.query.application;

import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class CurrentSourceQueryServiceIT {

    private static final String R1 = "1".repeat(40);
    private static final String R2 = "2".repeat(40);
    private static final String DIGEST_1 = "1".repeat(64);
    private static final String DIGEST_2 = "2".repeat(64);
    private static MongoDBContainer container;
    private static MongoTemplate template;

    @BeforeAll
    static void startMongo() {
        container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"));
        container.start();
        template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic_query_source_test");
    }

    @AfterAll
    static void stopMongo() {
        container.stop();
    }

    @BeforeEach
    void clearDatabase() {
        template.getDb().drop();
    }

    @Test
    void reads_only_the_source_artifact_referenced_by_the_current_generation() {
        seedCurrent("orders", R1, "g1", DIGEST_1);
        seedManifest("orders", R1, "g1", DIGEST_1);
        seedSource("orders", "g1", "src/Order.java", "artifact-old", "class Order { String revision = \"R1\"; }");
        seedManifest("orders", R2, "g2", DIGEST_2);
        seedSource("orders", "g2", "src/Order.java", "artifact-current", "class Order { String revision = \"R2\"; }");
        seedCurrent("orders", R2, "g2", DIGEST_2);

        CurrentSourceQueryService service = sourceService(List.of());

        assertThatThrownBy(() -> service.getSource("orders", R1, "src/Order.java"))
                .isInstanceOfSatisfying(RevisionOutdatedException.class,
                        exception -> assertThat(exception.currentRevision().value()).isEqualTo(R2));
        assertThat(service.getSource("orders", R2, "src/Order.java").utf8Content())
                .contains("R2").doesNotContain("R1");
    }

    @Test
    void prevents_a_shared_artifact_from_becoming_cross_repository_source_authority() {
        seedCurrent("orders", R1, "g1", DIGEST_1);
        seedManifest("orders", R1, "g1", DIGEST_1);
        seedSource("orders", "g1", "src/Order.java", "shared-artifact", "class Shared {}");
        seedCurrent("billing", R1, "g1", DIGEST_1);
        seedManifest("billing", R1, "g1", DIGEST_1);
        seedGenerationFile("billing", "g1", "src/Billing.java", "shared-artifact");

        assertThat(sourceService(List.of()).getSource("orders", R1, "src/Order.java").utf8Content()).isEqualTo("class Shared {}");
        assertThatThrownBy(() -> sourceService(List.of("billing")).getSource("billing", R1, "src/Billing.java"))
                .isInstanceOf(RepositoryNotFoundException.class)
                .hasMessage("REPOSITORY_NOT_FOUND");
    }

    @Test
    void follows_an_authorized_bounded_rollback_pointer_without_accepting_an_arbitrary_old_generation() {
        seedCurrent("orders", R2, "g2", DIGEST_2);
        seedManifest("orders", R1, "g1", DIGEST_1);
        seedManifest("orders", R2, "g2", DIGEST_2);
        seedSource("orders", "g1", "src/Order.java", "artifact-r1", "class Order { String revision = \"R1\"; }");
        seedSource("orders", "g2", "src/Order.java", "artifact-r2", "class Order { String revision = \"R2\"; }");
        template.getCollection("repositories").updateOne(new Document("repoId", "orders"),
                new Document("$set", new Document("rollbackPointer", pointer(R1, "g1", DIGEST_1))));
        template.getCollection("repositories").updateOne(new Document("repoId", "orders"),
                new Document("$set", pointer(R1, "g1", DIGEST_1)));

        CurrentRepositoryQueryService repositories = new CurrentRepositoryQueryService(selector(List.of()));
        CurrentSourceQueryService sources = sourceService(List.of());

        assertThat(repositories.getRepository("orders").revision().value()).isEqualTo(R1);
        assertThat(sources.getSource("orders", R1, "src/Order.java").utf8Content()).contains("R1");
        assertThatThrownBy(() -> sources.getSource("orders", R2, "src/Order.java"))
                .isInstanceOfSatisfying(RevisionOutdatedException.class,
                        exception -> assertThat(exception.currentRevision().value()).isEqualTo(R1));
    }

    private CurrentSourceQueryService sourceService(List<String> forbiddenRepositories) {
        return new CurrentSourceQueryService(template, selector(forbiddenRepositories));
    }

    private CurrentGenerationSelector selector(List<String> forbiddenRepositories) {
        return new CurrentGenerationSelector(template, new ConfiguredReadPolicy(new ReadPolicyProperties(
                forbiddenRepositories, List.of(), List.of(), List.of())), Duration.ofSeconds(2));
    }

    private void seedCurrent(String repositoryId, String revision, String generationId, String digest) {
        template.getCollection("repositories").deleteMany(new Document("repoId", repositoryId));
        template.getCollection("repositories").insertOne(new Document("repoId", repositoryId).append("revision", revision)
                .append("generationId", generationId).append("manifestDigest", digest).append("committedJobId", "job-" + generationId)
                .append("publishedAt", new java.util.Date()));
    }

    private Document pointer(String revision, String generationId, String digest) {
        return new Document("revision", revision).append("generationId", generationId).append("manifestDigest", digest)
                .append("committedJobId", "job-" + generationId).append("publishedAt", new java.util.Date());
    }

    private void seedManifest(String repositoryId, String revision, String generationId, String digest) {
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId).append("sourceRevision", revision)
                .append("generationId", generationId).append("identityDigest", digest).append("writeState", "SEALED_VALID")
                .append("schemaVersion", 1).append("projectionVersions", List.of(new Document("name", "SOURCES").append("version", 1),
                        new Document("name", "SYMBOLS").append("version", 1), new Document("name", "RELATIONS").append("version", 1),
                        new Document("name", "ENTRY_POINTS").append("version", 1), new Document("name", "SEARCH").append("version", 1))));
    }

    private void seedSource(String repositoryId, String generationId, String path, String artifactId, String content) {
        template.getCollection("source_artifacts").replaceOne(new Document("sourceArtifactId", artifactId),
                new Document("sourceArtifactId", artifactId).append("contentHash", artifactId).append("utf8Content", content),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
        seedGenerationFile(repositoryId, generationId, path, artifactId);
    }

    private void seedGenerationFile(String repositoryId, String generationId, String path, String artifactId) {
        template.getCollection("generation_files").insertOne(new Document("repoId", repositoryId).append("generationId", generationId)
                .append("sourcePath", path).append("sourceArtifactId", artifactId).append("contentHash", artifactId));
    }
}
