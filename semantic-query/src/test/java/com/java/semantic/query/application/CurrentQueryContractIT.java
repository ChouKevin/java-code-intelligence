package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
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
class CurrentQueryContractIT {
    private static final String REVISION = "1".repeat(40);
    private static final String DIGEST = "2".repeat(64);
    private static MongoDBContainer container;
    private static MongoTemplate template;

    @BeforeAll
    static void startMongo() {
        container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"));
        container.start();
        template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic_query_contract_test");
    }

    @AfterAll
    static void stopMongo() { container.stop(); }

    @BeforeEach
    void clearDatabase() { template.getDb().drop(); }

    @Test
    void reads_production_shaped_sources_and_fails_closed_for_forbidden_typed_identities() {
        SourceTypeIdentity type = sourceType();
        seedCurrent("orders");
        seedManifest("orders", true);
        String content = "package example.api; class OrderService {}";
        SourceArtifactDocument artifact = SourceArtifactDocument.create(content);
        template.getCollection("source_artifacts").insertOne(new Document("sourceArtifactId", artifact.id().value())
                .append("contentHash", artifact.contentHash()).append("utf8Content", content));
        GenerationFileDocument mapping = new GenerationFileDocument(new RepositoryId("orders"), new GenerationId("g1"),
                type.sourceFile(), new SourceArtifactId(artifact.id().value()), artifact.contentHash());
        Document stored = new Document();
        template.getConverter().write(mapping, stored);
        stored.put("repoId", "orders");
        stored.put("generationId", "g1");
        template.getCollection("generation_files").insertOne(stored);

        assertThat(sourceService(policy()).getSource("orders", REVISION, type).utf8Content()).isEqualTo(content);
        assertThatThrownBy(() -> sourceService(policy(new ReadPolicyProperties.PackageRule("orders", "example")))
                .getSource("orders", REVISION, type)).isInstanceOf(RepositoryNotFoundException.class);

        template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set",
                new Document("revision", "3".repeat(40)).append("generationId", "g2").append("manifestDigest", "4".repeat(64))));
        assertThatThrownBy(() -> sourceService(policy()).getSource("orders", REVISION, type))
                .isInstanceOf(RevisionOutdatedException.class);
    }

    @Test
    void distinguishes_absent_unpublished_denied_and_incompatible_catalog_entries() {
        template.getCollection("repositories").insertOne(new Document("repoId", "unpublished"));
        seedCurrent("published");
        seedManifest("published", true);
        seedCurrent("incompatible");
        seedManifest("incompatible", false);
        CurrentRepositoryQueryService visible = new CurrentRepositoryQueryService(selector(policy()));

        assertThat(visible.listRepositories()).extracting(current -> current.repositoryId().value()).containsExactly("published");
        assertThatThrownBy(() -> visible.getRepository("absent")).isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> visible.getRepository("unpublished")).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> visible.getRepository("incompatible")).isInstanceOf(IndexContractMismatchException.class);
        assertThatThrownBy(() -> new CurrentRepositoryQueryService(selector(policy("published"))).getRepository("published"))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void derives_symbol_ids_from_typed_code_fact_identities_before_querying() {
        MethodTarget method = new MethodTarget(sourceType(), "find", List.of("java.lang.String"));
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.METHOD, method);
        seedCurrent("orders");
        seedManifest("orders", true);
        template.getCollection("symbols").insertOne(new Document("repoId", "orders").append("generationId", "g1")
                .append("symbolId", CodeFactId.from(identity).value()).append("canonical", identity.canonicalForm())
                .append("sourcePath", method.sourceFile()));
        CurrentSymbolQueryService service = new CurrentSymbolQueryService(template, selector(policy()), Duration.ofSeconds(2));

        assertThat(service.getSymbol("orders", REVISION, identity).symbolId()).isEqualTo(CodeFactId.from(identity).value());
        assertThatThrownBy(() -> new CurrentSymbolQueryService(template, selector(policy(new ReadPolicyProperties.MethodRule(
                "orders", "example.api", "OrderService", "find", List.of("java.lang.String")))), Duration.ofSeconds(2))
                .getSymbol("orders", REVISION, identity)).isInstanceOf(RepositoryNotFoundException.class);
    }

    private CurrentSourceQueryService sourceService(ConfiguredReadPolicy policy) {
        return new CurrentSourceQueryService(template, selector(policy), Duration.ofSeconds(2));
    }

    private CurrentGenerationSelector selector(ConfiguredReadPolicy policy) {
        return new CurrentGenerationSelector(template, policy, Duration.ofSeconds(2));
    }

    private ConfiguredReadPolicy policy() { return policy(List.of(), List.of(), List.of()); }
    private ConfiguredReadPolicy policy(String forbiddenRepository) { return policy(List.of(forbiddenRepository), List.of(), List.of()); }
    private ConfiguredReadPolicy policy(ReadPolicyProperties.PackageRule packageRule) { return policy(List.of(), List.of(packageRule), List.of()); }
    private ConfiguredReadPolicy policy(ReadPolicyProperties.MethodRule methodRule) { return policy(List.of(), List.of(), List.of(methodRule)); }
    private ConfiguredReadPolicy policy(List<String> repositories, List<ReadPolicyProperties.PackageRule> packages,
                                       List<ReadPolicyProperties.MethodRule> methods) {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(repositories, packages, List.of(), methods));
    }

    private void seedCurrent(String repositoryId) {
        template.getCollection("repositories").insertOne(new Document("repoId", repositoryId).append("revision", REVISION)
                .append("generationId", "g1").append("manifestDigest", DIGEST).append("committedJobId", "job-1")
                .append("publishedAt", new java.util.Date()));
    }

    private void seedManifest(String repositoryId, boolean compatible) {
        List<Document> projections = compatible ? List.of(new Document("name", "SOURCES").append("version", 1),
                new Document("name", "SYMBOLS").append("version", 1), new Document("name", "RELATIONS").append("version", 1),
                new Document("name", "ENTRY_POINTS").append("version", 1), new Document("name", "SEARCH").append("version", 1))
                : List.of(new Document("name", "SOURCES").append("version", 0));
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId).append("sourceRevision", REVISION)
                .append("generationId", "g1").append("identityDigest", DIGEST).append("writeState", "SEALED_VALID")
                .append("schemaVersion", 1).append("projectionVersions", projections));
    }

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(new JavaTypeIdentity("example.api", "OrderService"), "src/main/java/example/api/OrderService.java");
    }
}
