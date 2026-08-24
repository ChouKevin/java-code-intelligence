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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class CurrentRepositoryQueryServiceIT {

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
        template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic_query_repository_test");
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
    void returns_only_authorized_current_published_repositories() {
        seedCurrent("orders", R1, "g1", DIGEST_1);
        seedCurrent("forbidden", R1, "g1", DIGEST_1);
        template.getCollection("repositories").insertOne(new Document("repoId", "not-ready"));

        CurrentRepositoryQueryService service = repositoryService(List.of("forbidden"));

        assertThat(service.listRepositories()).extracting(repository -> repository.repositoryId().value())
                .containsExactly("orders");
        assertThat(service.getRepository("orders").revision().value()).isEqualTo(R1);
        assertThatThrownBy(() -> service.getRepository("forbidden"))
                .isInstanceOf(RepositoryNotFoundException.class)
                .hasMessage("REPOSITORY_NOT_FOUND");
    }

    @Test
    void returns_index_not_ready_and_stale_revision_without_falling_back_to_an_old_generation() {
        template.getCollection("repositories").insertOne(new Document("repoId", "not-ready"));
        seedCurrent("orders", R2, "g2", DIGEST_2);
        seedManifest("orders", R2, "g2", DIGEST_2, true);
        seedManifest("orders", R1, "g1", DIGEST_1, true);

        CurrentGenerationSelector selector = selector(List.of());

        assertThatThrownBy(() -> selector.select("not-ready", R1, CurrentGenerationSelector.SOURCES))
                .isInstanceOf(IndexNotReadyException.class)
                .hasMessage("INDEX_NOT_READY");
        assertThatThrownBy(() -> selector.select("orders", R1, CurrentGenerationSelector.SOURCES))
                .isInstanceOfSatisfying(RevisionOutdatedException.class, exception -> {
                    assertThat(exception.requestedRevision().value()).isEqualTo(R1);
                    assertThat(exception.currentRevision().value()).isEqualTo(R2);
                });
        assertThat(selector.select("orders", R2, CurrentGenerationSelector.SOURCES).generationId().value()).isEqualTo("g2");
    }

    @Test
    void rejects_a_current_pointer_when_its_sealed_manifest_cannot_meet_the_query_contract() {
        seedCurrent("orders", R1, "g1", DIGEST_1);
        seedManifest("orders", R1, "g1", DIGEST_1, false);

        assertThatThrownBy(() -> selector(List.of()).select("orders", R1, CurrentGenerationSelector.SOURCES))
                .isInstanceOf(IndexContractMismatchException.class)
                .hasMessage("INDEX_CONTRACT_MISMATCH");
    }

    @Test
    void reads_a_symbol_only_from_the_authorized_current_generation() {
        seedCurrent("orders", R1, "g1", DIGEST_1);
        seedManifest("orders", R1, "g1", DIGEST_1, true);
        template.getCollection("symbols").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("symbolId", "order-service").append("canonical", "TYPE:com.example.OrderService")
                .append("sourcePath", "src/OrderService.java"));

        CurrentSymbolQueryService service = new CurrentSymbolQueryService(template, selector(List.of()));

        assertThat(service.getSymbol("orders", R1, "order-service").canonicalIdentity())
                .isEqualTo("TYPE:com.example.OrderService");
    }

    @ParameterizedTest
    @MethodSource("hiddenRepositories")
    void makes_denied_missing_not_ready_current_and_stale_repositories_indistinguishable(String repositoryId, String revision) {
        seedCurrent("denied-current", R1, "g1", DIGEST_1);
        seedManifest("denied-current", R1, "g1", DIGEST_1, true);
        seedCurrent("denied-stale", R2, "g2", DIGEST_2);
        seedManifest("denied-stale", R2, "g2", DIGEST_2, true);
        template.getCollection("repositories").insertOne(new Document("repoId", "denied-not-ready"));

        assertThatThrownBy(() -> selector(List.of("denied-current", "denied-stale", "denied-not-ready", "missing"))
                .select(repositoryId, revision, CurrentGenerationSelector.SOURCES))
                .isInstanceOf(RepositoryNotFoundException.class)
                .hasMessage("REPOSITORY_NOT_FOUND")
                .hasNoCause();
    }

    @Test
    void maps_a_bounded_storage_timeout_to_semantic_index_unavailable() {
        MongoTemplate unavailableTemplate = new MongoTemplate(MongoClients.create(
                "mongodb://127.0.0.1:1/semantic?serverSelectionTimeoutMS=100&connectTimeoutMS=100"), "semantic");
        CurrentGenerationSelector unavailable = new CurrentGenerationSelector(unavailableTemplate,
                new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of())), Duration.ofMillis(250));

        assertThatThrownBy(() -> unavailable.select("orders", R1, CurrentGenerationSelector.SOURCES))
                .isInstanceOf(SemanticIndexUnavailableException.class)
                .hasMessage("SEMANTIC_INDEX_UNAVAILABLE");
    }

    private static Stream<Arguments> hiddenRepositories() {
        return Stream.of(
                Arguments.of("missing", R1),
                Arguments.of("denied-not-ready", R1),
                Arguments.of("denied-current", R1),
                Arguments.of("denied-stale", R1));
    }

    private CurrentRepositoryQueryService repositoryService(List<String> forbiddenRepositories) {
        return new CurrentRepositoryQueryService(selector(forbiddenRepositories));
    }

    private CurrentGenerationSelector selector(List<String> forbiddenRepositories) {
        return new CurrentGenerationSelector(template, new ConfiguredReadPolicy(new ReadPolicyProperties(
                forbiddenRepositories, List.of(), List.of(), List.of())), Duration.ofSeconds(2));
    }

    private void seedCurrent(String repositoryId, String revision, String generationId, String digest) {
        template.getCollection("repositories").deleteMany(new Document("repoId", repositoryId));
        template.getCollection("repositories").insertOne(new Document("repoId", repositoryId)
                .append("revision", revision).append("generationId", generationId).append("manifestDigest", digest)
                .append("committedJobId", "job-" + generationId).append("publishedAt", new java.util.Date()));
    }

    private void seedManifest(String repositoryId, String revision, String generationId, String digest, boolean compatible) {
        template.getCollection("generation_manifests").deleteMany(new Document("repoId", repositoryId).append("generationId", generationId));
        List<Document> projections = compatible
                ? List.of(new Document("name", "SOURCES").append("version", 1), new Document("name", "SYMBOLS").append("version", 1),
                new Document("name", "RELATIONS").append("version", 1), new Document("name", "ENTRY_POINTS").append("version", 1),
                new Document("name", "SEARCH").append("version", 1))
                : List.of(new Document("name", "SOURCES").append("version", 0));
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId)
                .append("sourceRevision", revision).append("generationId", generationId).append("identityDigest", digest)
                .append("writeState", "SEALED_VALID").append("schemaVersion", 1).append("projectionVersions", projections));
    }
}
