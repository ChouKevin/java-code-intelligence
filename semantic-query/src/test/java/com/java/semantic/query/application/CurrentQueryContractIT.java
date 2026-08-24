package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.BsonDocument;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class CurrentQueryContractIT {
    private static final String REVISION = "1".repeat(40);
    private static final String REVISION_2 = "3".repeat(40);
    private static final String DIGEST = "2".repeat(64);
    private static final String DIGEST_2 = "4".repeat(64);
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
        SourceArtifactDocument artifact = seedSource("orders", "g1", type.sourceFile(), content);
        seedTypeSymbol("orders", REVISION, "g1", type, artifact.id());

        assertThat(sourceService(policy()).getSource("orders", REVISION, type).utf8Content()).isEqualTo(content);
        assertThatThrownBy(() -> sourceService(policy(new ReadPolicyProperties.PackageRule("orders", "example")))
                .getSource("orders", REVISION, type)).isInstanceOf(RepositoryNotFoundException.class);

        seedManifest("orders", REVISION_2, "g2", DIGEST_2, true);
        String currentContent = "package example.api; class OrderService { String revision = \"R2\"; }";
        SourceArtifactDocument currentArtifact = seedSource("orders", "g2", type.sourceFile(), currentContent);
        seedTypeSymbol("orders", REVISION_2, "g2", type, currentArtifact.id());
        seedCurrent("orders", REVISION_2, "g2", DIGEST_2);

        assertThatThrownBy(() -> sourceService(policy()).getSource("orders", REVISION, type))
                .isInstanceOfSatisfying(RevisionOutdatedException.class,
                        exception -> assertThat(exception.currentRevision().value()).isEqualTo(REVISION_2));
        assertThat(sourceService(policy()).getSource("orders", REVISION_2, type).utf8Content()).isEqualTo(currentContent);

        seedCurrent("orders", REVISION, "g1", DIGEST);
        assertThat(sourceService(policy()).getSource("orders", REVISION, type).utf8Content()).isEqualTo(content);
        assertThatThrownBy(() -> sourceService(policy()).getSource("orders", REVISION_2, type))
                .isInstanceOfSatisfying(RevisionOutdatedException.class,
                        exception -> assertThat(exception.currentRevision().value()).isEqualTo(REVISION));
    }

    @Test
    void distinguishes_absent_unpublished_denied_and_incompatible_catalog_entries() {
        template.getCollection("repositories").insertOne(new Document("repoId", "unpublished"));
        seedCurrent("published");
        seedManifest("published", true);
        seedCurrent("incompatible");
        seedManifest("incompatible", false);
        seedCurrent("malformed");
        seedManifest("malformed", true);
        template.getCollection("generation_manifests").updateOne(new Document("repoId", "malformed"),
                new Document("$set", new Document("schemaVersion", "1")));
        CurrentRepositoryQueryService visible = new CurrentRepositoryQueryService(selector(policy()));

        assertThat(visible.listRepositories()).extracting(current -> current.repositoryId().value()).containsExactly("published");
        assertThatThrownBy(() -> visible.getRepository("absent")).isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> visible.getRepository("unpublished")).isInstanceOf(IndexNotReadyException.class);
        assertThatThrownBy(() -> visible.getRepository("incompatible")).isInstanceOf(IndexContractMismatchException.class);
        assertThatThrownBy(() -> visible.getRepository("malformed")).isInstanceOf(IndexContractMismatchException.class);
        assertThatThrownBy(() -> new CurrentRepositoryQueryService(selector(policy("published"))).getRepository("published"))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void derives_symbol_ids_from_typed_code_fact_identities_before_querying() {
        MethodTarget method = new MethodTarget(sourceType(), "find", List.of("java.lang.String"));
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.METHOD, method);
        seedCurrent("orders");
        seedManifest("orders", true);
        seedMethodSymbol("orders", REVISION, "g1", method);
        CurrentSymbolQueryService service = new CurrentSymbolQueryService(template, selector(policy()), Duration.ofSeconds(2));

        assertThat(service.getSymbol("orders", REVISION, identity).symbolId()).isEqualTo(CodeFactId.from(identity).value());
        assertThatThrownBy(() -> new CurrentSymbolQueryService(template, selector(policy(new ReadPolicyProperties.MethodRule(
                "orders", "example.api", "OrderService", "find", List.of("java.lang.String")))), Duration.ofSeconds(2))
                .getSymbol("orders", REVISION, identity)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void hides_forbidden_repositories_before_validating_typed_identities() {
        assertThatThrownBy(() -> sourceService(policy("orders")).getSource("orders", REVISION, null))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> new CurrentSymbolQueryService(template, selector(policy("orders")), Duration.ofSeconds(2))
                .getSymbol("orders", REVISION, null)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void denies_source_reads_when_a_requested_type_is_spoofed_or_a_colocated_declaration_is_denied() {
        SourceTypeIdentity allowed = new SourceTypeIdentity(new JavaTypeIdentity("example.api", "PublicFacade"),
                "src/main/java/example/api/Shared.java");
        SourceTypeIdentity denied = new SourceTypeIdentity(new JavaTypeIdentity("example.api", "SecretAdmin"),
                allowed.sourceFile());
        seedCurrent("orders");
        seedManifest("orders", true);
        SourceArtifactDocument artifact = seedSource("orders", "g1", allowed.sourceFile(), "class PublicFacade {} class SecretAdmin {}");
        seedTypeSymbol("orders", REVISION, "g1", allowed, artifact.id());
        seedTypeSymbol("orders", REVISION, "g1", denied, artifact.id());

        assertThatThrownBy(() -> sourceService(policy(new ReadPolicyProperties.ClassRule(
                "orders", "example.api", "SecretAdmin"))).getSource("orders", REVISION, allowed))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void denies_a_persisted_mapper_symbol_when_its_namespace_method_is_forbidden() {
        MapperStatementIdentity statement = new MapperStatementIdentity("example.api.OrderMapper", "find",
                "src/main/resources/OrderMapper.xml");
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                CodeFactKind.MAPPER_STATEMENT, statement);
        seedCurrent("orders");
        seedManifest("orders", true);
        seedMapperSymbol("orders", REVISION, "g1", statement);

        assertThatThrownBy(() -> new CurrentSymbolQueryService(template, selector(policy(new ReadPolicyProperties.MethodRule(
                "orders", "example.api", "OrderMapper", "find", List.of("java.lang.String")))), Duration.ofSeconds(2))
                .getSymbol("orders", REVISION, identity)).isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void reads_a_valid_empty_source_artifact() {
        SourceTypeIdentity type = sourceType();
        seedCurrent("orders");
        seedManifest("orders", true);
        SourceArtifactDocument artifact = seedSource("orders", "g1", type.sourceFile(), "");
        seedTypeSymbol("orders", REVISION, "g1", type, artifact.id());

        assertThat(sourceService(policy()).getSource("orders", REVISION, type).utf8Content()).isEmpty();
    }

    @Test
    void normalizes_nested_classes_and_parameter_forms_without_overmatching_package_boundaries() {
        RepositoryId repositoryId = new RepositoryId("orders");
        SourceTypeIdentity nestedType = new SourceTypeIdentity(new JavaTypeIdentity("example.api", "Outer.Inner"),
                "src/main/java/example/api/Outer.java");
        MethodTarget method = new MethodTarget(nestedType, "save", List.of("java.util.List<java.lang.String>..."));
        ConfiguredReadPolicy classPolicy = policy(new ReadPolicyProperties.ClassRule("orders", "example.api", "Outer$Inner"));
        ConfiguredReadPolicy methodPolicy = policy(new ReadPolicyProperties.MethodRule(
                "orders", "example.api", "Outer.Inner", "save", List.of("List[]")));
        ConfiguredReadPolicy packagePolicy = policy(new ReadPolicyProperties.PackageRule("orders", "example.api"));

        assertThat(classPolicy.isSourceVisible(repositoryId, nestedType)).isFalse();
        assertThat(methodPolicy.isCodeFactVisible(repositoryId, new CodeFactIdentity(repositoryId, new RepositoryRevision(REVISION),
                CodeFactKind.METHOD, method))).isFalse();
        assertThat(packagePolicy.isSourceVisible(repositoryId, new SourceTypeIdentity(new JavaTypeIdentity("example.apix", "Visible"),
                "src/main/java/example/apix/Visible.java"))).isTrue();
    }

    @Test
    void keeps_shared_artifacts_scoped_by_current_repository_generation_membership() {
        SourceTypeIdentity ordersType = sourceType();
        SourceTypeIdentity billingType = new SourceTypeIdentity(new JavaTypeIdentity("example.billing", "BillingService"),
                ordersType.sourceFile());
        seedCurrent("orders");
        seedManifest("orders", true);
        seedCurrent("billing");
        seedManifest("billing", true);
        String content = "package example.api; class OrderService {}";
        SourceArtifactDocument shared = seedSource("orders", "g1", ordersType.sourceFile(), content);
        seedTypeSymbol("orders", REVISION, "g1", ordersType, shared.id());
        seedTypeSymbol("billing", REVISION, "g1", billingType, shared.id());

        assertThatThrownBy(() -> sourceService(policy()).getSource("billing", REVISION, billingType))
                .isInstanceOf(IndexNotReadyException.class);

        seedGenerationFile("billing", "g1", billingType.sourceFile(), shared);
        assertThat(sourceService(policy()).getSource("billing", REVISION, billingType).utf8Content()).isEqualTo(content);
        assertThatThrownBy(() -> sourceService(policy("billing")).getSource("billing", REVISION, billingType))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    void hides_missing_unpublished_current_and_stale_repositories_with_one_result() {
        template.getCollection("repositories").insertOne(new Document("repoId", "hidden-unpublished"));
        seedCurrent("hidden-current");
        seedManifest("hidden-current", true);
        seedCurrent("hidden-stale", REVISION_2, "g2", DIGEST_2);
        seedManifest("hidden-stale", REVISION_2, "g2", DIGEST_2, true);

        for (String repositoryId : List.of("hidden-missing", "hidden-unpublished", "hidden-current", "hidden-stale")) {
            assertThatThrownBy(() -> sourceService(policy(repositoryId)).getSource(repositoryId, REVISION, null))
                    .isInstanceOf(RepositoryNotFoundException.class)
                    .hasMessage("REPOSITORY_NOT_FOUND")
                    .hasNoCause();
        }
    }

    @Test
    void maps_unavailable_storage_and_bounds_every_source_read_without_rereading_the_pointer() {
        try (MongoClient unavailableClient = MongoClients.create(
                "mongodb://127.0.0.1:1/semantic?serverSelectionTimeoutMS=100&connectTimeoutMS=100")) {
            MongoTemplate unavailableTemplate = new MongoTemplate(unavailableClient, "semantic");
            CurrentSourceQueryService unavailable = new CurrentSourceQueryService(unavailableTemplate,
                    new CurrentGenerationSelector(unavailableTemplate, policy(), Duration.ofMillis(250)), Duration.ofMillis(250));
            assertThatThrownBy(() -> unavailable.getSource("orders", REVISION, sourceType()))
                    .isInstanceOf(SemanticIndexUnavailableException.class)
                    .hasMessage("SEMANTIC_INDEX_UNAVAILABLE");
        }

        SourceTypeIdentity type = sourceType();
        seedCurrent("orders");
        seedManifest("orders", true);
        SourceArtifactDocument artifact = seedSource("orders", "g1", type.sourceFile(), "class OrderService {}");
        seedTypeSymbol("orders", REVISION, "g1", type, artifact.id());
        Duration timeout = Duration.ofMillis(750);
        List<BsonDocument> commands = new CopyOnWriteArrayList<>();
        CommandListener listener = new CommandListener() {
            @Override
            public void commandStarted(CommandStartedEvent event) {
                if ("find".equals(event.getCommandName())) {
                    commands.add(event.getCommand().clone());
                }
            }
        };
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(container.getConnectionString()))
                .addCommandListener(listener)
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            MongoTemplate observedTemplate = new MongoTemplate(client, "semantic_query_contract_test");
            CurrentGenerationSelector observedSelector = new CurrentGenerationSelector(observedTemplate, policy(), timeout);
            CurrentSourceQueryService observedService = new CurrentSourceQueryService(observedTemplate, observedSelector, timeout);

            assertThat(observedService.getSource("orders", REVISION, type).utf8Content()).isEqualTo("class OrderService {}");
        }

        List<String> expectedCollections = List.of(
                "repositories", "generation_manifests", "symbols", "generation_files", "source_artifacts");
        assertThat(commands).extracting(command -> command.getString("find").getValue()).containsAll(expectedCollections);
        assertThat(commands).filteredOn(command -> expectedCollections.contains(command.getString("find").getValue()))
                .allSatisfy(command -> assertThat(command.getNumber("maxTimeMS").longValue()).isEqualTo(timeout.toMillis()));
        assertThat(commands).filteredOn(command -> "repositories".equals(command.getString("find").getValue())).hasSize(1);
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
    private ConfiguredReadPolicy policy(ReadPolicyProperties.ClassRule classRule) {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(classRule), List.of()));
    }
    private ConfiguredReadPolicy policy(ReadPolicyProperties.MethodRule methodRule) { return policy(List.of(), List.of(), List.of(methodRule)); }
    private ConfiguredReadPolicy policy(List<String> repositories, List<ReadPolicyProperties.PackageRule> packages,
                                       List<ReadPolicyProperties.MethodRule> methods) {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(repositories, packages, List.of(), methods));
    }

    private void seedCurrent(String repositoryId) {
        seedCurrent(repositoryId, REVISION, "g1", DIGEST);
    }

    private void seedCurrent(String repositoryId, String revision, String generationId, String digest) {
        Document pointer = new Document("repoId", repositoryId).append("revision", revision)
                .append("generationId", generationId).append("manifestDigest", digest).append("committedJobId", "job-" + generationId)
                .append("publishedAt", new java.util.Date());
        template.getCollection("repositories").replaceOne(new Document("repoId", repositoryId), pointer,
                new ReplaceOptions().upsert(true));
    }

    private void seedManifest(String repositoryId, boolean compatible) {
        seedManifest(repositoryId, REVISION, "g1", DIGEST, compatible);
    }

    private void seedManifest(String repositoryId, String revision, String generationId, String digest, boolean compatible) {
        List<Document> projections = compatible ? List.of(new Document("name", "SOURCES").append("version", 1),
                new Document("name", "SYMBOLS").append("version", 1), new Document("name", "RELATIONS").append("version", 1),
                new Document("name", "ENTRY_POINTS").append("version", 1), new Document("name", "SEARCH").append("version", 1))
                : List.of(new Document("name", "SOURCES").append("version", 0));
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId).append("sourceRevision", revision)
                .append("generationId", generationId).append("identityDigest", digest).append("writeState", "SEALED_VALID")
                .append("schemaVersion", 1).append("projectionVersions", projections));
    }

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(new JavaTypeIdentity("example.api", "OrderService"), "src/main/java/example/api/OrderService.java");
    }

    private SourceArtifactDocument seedSource(String repositoryId, String generationId, String sourcePath, String content) {
        SourceArtifactDocument artifact = SourceArtifactDocument.create(content);
        template.getCollection("source_artifacts").replaceOne(new Document("sourceArtifactId", artifact.id().value()),
                new Document("sourceArtifactId", artifact.id().value()).append("contentHash", artifact.contentHash())
                        .append("utf8Content", content), new ReplaceOptions().upsert(true));
        seedGenerationFile(repositoryId, generationId, sourcePath, artifact);
        return artifact;
    }

    private void seedGenerationFile(String repositoryId, String generationId, String sourcePath, SourceArtifactDocument artifact) {
        GenerationFileDocument mapping = new GenerationFileDocument(new RepositoryId(repositoryId), new GenerationId(generationId),
                sourcePath, artifact.id(), artifact.contentHash());
        Document stored = new Document();
        template.getConverter().write(mapping, stored);
        stored.put("repoId", repositoryId);
        stored.put("generationId", generationId);
        template.getCollection("generation_files").insertOne(stored);
    }

    private void seedTypeSymbol(String repositoryId, String revision, String generationId, SourceTypeIdentity sourceType,
                                SourceArtifactId artifactId) {
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId(repositoryId), new RepositoryRevision(revision),
                CodeFactKind.TYPE, sourceType);
        com.java.semantic.model.codefact.CodeFact fact = new com.java.semantic.model.codefact.CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(new RepositoryId(repositoryId), new GenerationId(generationId), fact,
                CodeFactKind.TYPE, sourceType.fullyQualifiedName(), sourceType.javaType().className(), sourceType.canonicalForm(),
                new DeclaredType(sourceType.fullyQualifiedName()), java.util.Set.of(), List.of(), artifactId,
                new SourceRange(sourceType.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
        Document stored = new Document();
        template.getConverter().write(symbol, stored);
        stored.put("repoId", repositoryId);
        stored.put("generationId", generationId);
        stored.put("symbolId", fact.id().value());
        stored.put("canonical", identity.canonicalForm());
        stored.put("sourcePath", sourceType.sourceFile());
        template.getCollection("symbols").insertOne(stored);
    }

    private void seedMethodSymbol(String repositoryId, String revision, String generationId, MethodTarget method) {
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId(repositoryId), new RepositoryRevision(revision),
                CodeFactKind.METHOD, method);
        com.java.semantic.model.codefact.CodeFact fact = new com.java.semantic.model.codefact.CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(new RepositoryId(repositoryId), new GenerationId(generationId), fact,
                CodeFactKind.METHOD, method.fullyQualifiedClassName(), method.methodName(), method.canonicalForm(),
                new DeclaredType("void"), java.util.Set.of(), List.of(), new SourceArtifactId("a".repeat(64)),
                new SourceRange(method.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
        Document stored = new Document();
        template.getConverter().write(symbol, stored);
        stored.put("repoId", repositoryId);
        stored.put("generationId", generationId);
        stored.put("symbolId", fact.id().value());
        stored.put("canonical", identity.canonicalForm());
        stored.put("sourcePath", method.sourceFile());
        template.getCollection("symbols").insertOne(stored);
    }

    private void seedMapperSymbol(String repositoryId, String revision, String generationId, MapperStatementIdentity statement) {
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId(repositoryId), new RepositoryRevision(revision),
                CodeFactKind.MAPPER_STATEMENT, statement);
        com.java.semantic.model.codefact.CodeFact fact = new com.java.semantic.model.codefact.CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(new RepositoryId(repositoryId), new GenerationId(generationId), fact,
                CodeFactKind.MAPPER_STATEMENT, statement.namespace(), statement.statementId(), statement.canonicalForm(),
                new DeclaredType("mapper-statement"), java.util.Set.of(), List.of(), new SourceArtifactId("a".repeat(64)),
                new SourceRange(statement.resourcePath(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
        Document stored = new Document();
        template.getConverter().write(symbol, stored);
        stored.put("repoId", repositoryId);
        stored.put("generationId", generationId);
        stored.put("symbolId", fact.id().value());
        stored.put("canonical", identity.canonicalForm());
        stored.put("sourcePath", statement.resourcePath());
        template.getCollection("symbols").insertOne(stored);
    }
}
