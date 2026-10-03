package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceStructure;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;
import com.java.semantic.semantic.adapter.jdtls.DefaultJdtWorkspaceManager;
import com.java.semantic.semantic.adapter.jdtls.JdtLsHomeRequirement;
import com.java.semantic.semantic.adapter.jdtls.JdtLsProcessFactory;
import com.java.semantic.semantic.adapter.jdtls.JdtLsReadinessProbe;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceLifecycleMetrics;
import com.java.semantic.semantic.adapter.jdtls.Lsp4jJavaSemanticService;
import com.java.semantic.query.SemanticQueryApplication;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Exercises a real JDT LS process before exporting fixture source through production projectors. */
@Tag("jdtls-it")
class FixtureFullIndexJdtLsIT {

    private static final Path VIDEO_FIXTURE = Path.of("fixtures/uat/video-service");
    private static final Path PAYMENT_FIXTURE = Path.of("fixtures/uat/payment-service");
    private static final Path ORDER_FIXTURE = Path.of("fixtures/uat/order-service");
    private static final String TOKEN_HEADER = "X-Api-Token";
    private static final String QUERY_TOKEN = "fixture-query-token";

    @TempDir
    Path temporaryDirectory;

    @Test
    void applies_the_reviewed_payment_v2_patch_once_to_the_semantic_owned_v1_fixture() throws IOException, InterruptedException {
        Path paymentRoot = copyFixture(PAYMENT_FIXTURE, temporaryDirectory.resolve("payment-v1"));
        Path patch = Path.of("fixtures/uat/versions/payment-service-v2.patch").toAbsolutePath();

        applyPatch(paymentRoot, patch);

        assertThat(Files.readString(paymentRoot.resolve("src/main/java/com/example/payment/PaymentMethod.java")))
                .contains("MOBILE_PAYMENT");
        assertThat(Files.readString(paymentRoot.resolve("src/main/java/com/example/payment/PaymentQueryController.java")))
                .contains("PaymentMethod.MOBILE_PAYMENT");
        assertThatThrownBy(() -> applyPatch(paymentRoot, patch))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("payment-service-v2.patch");
    }

    @Test
    void exports_framework_api_contract_stub_fixture_with_complete_isolated_generations_after_starting_a_real_jdt_language_server()
            throws Exception {
        Path jdtLsHome = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        DefaultJdtWorkspaceManager manager = manager(jdtLsHome);
        try (GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8.0.4"))) {
            mongo.addExposedPort(27017);
            mongo.start();
            try (MongoClient client = MongoClients.create("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017))) {
                org.springframework.data.mongodb.core.MongoTemplate template = new org.springframework.data.mongodb.core.MongoTemplate(client, "fixture_index");
                new IndexSchemaBootstrap(template).bootstrap();
                Path paymentRoot = copyFixture(PAYMENT_FIXTURE, temporaryDirectory.resolve("payment-service"));
                Path videoRoot = copyFixture(VIDEO_FIXTURE, temporaryDirectory.resolve("video-service"));
                Path orderRoot = copyFixture(ORDER_FIXTURE, temporaryDirectory.resolve("order-service"));
                String paymentRevision = commitFixture(paymentRoot);
                String videoRevision = commitFixture(videoRoot);
                String orderRevision = commitFixture(orderRoot);
                List<SourceIndexBatch> payment = exportAndPersist(manager, template, paymentRoot, "payment-service",
                        paymentRevision, "payment-generation");
                List<SourceIndexBatch> video = exportAndPersist(manager, template, videoRoot, "video-service",
                        videoRevision, "video-generation");
                List<SourceIndexBatch> order = exportAndPersist(manager, template, orderRoot, "order-service",
                        orderRevision, "order-generation");
                publish(template, "payment-service", paymentRevision, "payment-generation", paymentRoot);
                publish(template, "video-service", videoRevision, "video-generation", videoRoot);
                RepositoryProperties repositories = new RepositoryProperties();
                Map<String, RepositoryProperties.RepositoryConfig> configs = new java.util.HashMap<>();
                for (Path fixtureRoot : List.of(paymentRoot, videoRoot, orderRoot)) {
                    RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
                    config.setUrl(fixtureRoot.toUri().toString()); config.setDefaultBranch("main");
                    configs.put(fixtureRoot.getFileName().toString(), config);
                }
                repositories.setRepositories(configs);
                new com.java.semantic.indexer.config.ConfiguredRepositoryPublisher(template,
                        new com.java.semantic.repository.application.RepositoryRuntimeRegistry(repositories)).publish();
                publish(template, "order-service", orderRevision, "order-generation", orderRoot);
                Document paymentManifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS)
                        .find(new Document("repoId", "payment-service").append("generationId", "payment-generation")).first();
                SourceStructure paymentStructure = template.getConverter().read(SourceStructure.class,
                        paymentManifest.get("structure", Document.class));
                // Eight Java files and one mapper XML share the package namespace.
                assertThat(paymentStructure.packageCounts()).containsExactlyEntriesOf(Map.of("com.example.payment", 9L));

                assertThat(video).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                        .contains("VideoFormat", "MP4", "WEBM", "MOV", "upload");
                assertThat(payment).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                        .contains("CREDIT_CARD", "BANK_TRANSFER", "WALLET", "calculate", "paymentMethods",
                                "FeeFormulaUnavailableException");
                assertThat(order).flatMap(SourceIndexBatch::symbols).extracting(document -> document.name())
                        .contains("Order", "PENDING", "CONFIRMED", "SHIPPED", "CANCELLED", "cancel");
                assertThat(payment).flatMap(SourceIndexBatch::entryPoints).extracting(document -> document.trigger().httpPath().orElseThrow())
                        .contains("/payment-methods");
                assertThat(template.getCollection(IndexCollections.ENTRY_POINTS).countDocuments(new Document("repoId", "video-service")
                        .append("generationId", "video-generation").append("path", "/videos"))).isEqualTo(1L);
                assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("repoId", "payment-service")
                        .append("generationId", "payment-generation").append("name", "CREDIT_CARD"))).isEqualTo(1L);
                assertThat(template.getCollection(IndexCollections.SYMBOLS).countDocuments(new Document("repoId", "order-service")
                        .append("generationId", "payment-generation"))).isZero();
                assertThat(video).flatMap(SourceIndexBatch::relations).extracting(document -> document.kind().name())
                        .contains("IMPLEMENTS", "OVERRIDES", "CALLS_OUTBOUND_API", "PUBLISHES_MESSAGE");
                List<RelationDocument> videoRelations = video.stream().flatMap(batch -> batch.relations().stream()).toList();
                RelationDocument videoDestination = videoRelations.stream()
                        .filter(document -> document.kind() == RelationKind.PUBLISHES_MESSAGE)
                        .filter(document -> document.target() instanceof RelationTarget.External external
                                && external.target().equals(new ExternalTarget.Destination("kafka", "video.uploaded")))
                        .findFirst().orElseThrow();
                assertThat(videoDestination.range().sourceFile())
                        .isEqualTo("src/main/java/com/example/video/VideoEventPublisher.java");
                assertThat(videoDestination.range().range().start().line()).isEqualTo(8);
                assertThat(videoDestination.range().range().start().character()).isEqualTo(27);
                RelationDocument videoEndpoint = videoRelations.stream()
                        .filter(document -> document.kind() == RelationKind.CALLS_OUTBOUND_API)
                        .filter(document -> document.target() instanceof RelationTarget.External external
                                && external.target().equals(new ExternalTarget.Endpoint("POST", "/transcoding/jobs")))
                        .findFirst().orElseThrow();
                assertThat(videoEndpoint.range().sourceFile())
                        .isEqualTo("src/main/java/com/example/video/DefaultVideoService.java");
                assertThat(videoEndpoint.range().range().start().line()).isEqualTo(8);
                assertThat(videoEndpoint.range().range().start().character()).isEqualTo(8);
                assertThat(payment).flatMap(SourceIndexBatch::relations).extracting(document -> document.kind().name())
                        .contains("READS_CONFIGURATION", "USES_SQL_IDENTIFIER", "USES_TYPE", "REFERENCES", "IMPLEMENTS", "OVERRIDES", "CALLS");
                List<Document> paymentRelations = template.getCollection(IndexCollections.RELATIONS)
                        .find(new Document("repoId", "payment-service").append("generationId", "payment-generation"))
                        .into(new java.util.ArrayList<>());
                Document paymentCall = paymentRelations.stream()
                        .filter(document -> document.getString("from").contains("PaymentFeeCalculator")
                                && document.getString("target").contains("FeeFormulaEvaluator")
                                && "CALLS".equals(document.getString("kind")))
                        .findFirst().orElseThrow();
                assertThat(template.getCollection(IndexCollections.RELATIONS).countDocuments(new Document("from", paymentCall.getString("from"))))
                        .isPositive();
                assertThat(template.getCollection(IndexCollections.RELATIONS).countDocuments(new Document("target", paymentCall.getString("target"))))
                        .isPositive();
                assertThat(template.getCollection(IndexCollections.RELATIONS).find(new Document("repoId", "video-service")
                        .append("generationId", "video-generation")).into(new java.util.ArrayList<>()))
                        .allSatisfy(document -> {
                            assertThat(document.getString("sourcePath")).isNotBlank();
                            assertThat(document.get("from")).isNotNull();
                            assertThat(document.get("target")).isNotNull();
                        });
                assertThat(template.getCollection(IndexCollections.SEARCH).find(new Document()).into(new java.util.ArrayList<>()))
                        .allSatisfy(document -> assertThat(document.getString("sourcePath")).isNotBlank());
                manager.shutdownAll();
                follows_real_mcp_fixture_journeys(mongo);
            }
        } finally {
            manager.shutdownAll();
        }
    }

    private static void follows_real_mcp_fixture_journeys(GenericContainer<?> mongo) {
        Path queryConfiguration = Path.of("../semantic-query/src/main/resources/application.yml").toAbsolutePath();
        assertThat(Files.isRegularFile(queryConfiguration)).isTrue();
        String mongoUri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017) + "/fixture_index";
        try (ConfigurableApplicationContext query = new SpringApplicationBuilder(SemanticQueryApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--spring.config.location=" + queryConfiguration.toUri(), "--spring.mongodb.uri=" + mongoUri,
                        "--semantic.query.api-token=" + QUERY_TOKEN, "--server.address=127.0.0.1", "--server.port=0")) {
            int port = ((WebServerApplicationContext) query).getWebServer().getPort();
            String baseUrl = "http://127.0.0.1:" + port;
            JsonMapper mapper = JsonMapper.builder().build();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl + "/mcp")
                    .jsonMapper(new JacksonMcpJsonMapper(mapper))
                    .httpRequestCustomizer((request, method, uri, body, context) -> request.header(TOKEN_HEADER, QUERY_TOKEN))
                    .build();
            try (McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(30))
                    .build()) {
                assertThat(client.initialize().serverInfo()).isNotNull();

                JourneyRecorder discovery = new JourneyRecorder("fixture-discovery");
                Map<?, ?> repositories = successfulBody(client, "list_repositories", Map.of(), mapper, discovery);
                Map<?, ?> paymentRepository = repository(repositories, "payment-service");
                Map<?, ?> videoRepository = repository(repositories, "video-service");
                String paymentRevision = String.valueOf(paymentRepository.get("publishedRevision"));
                String videoRevision = String.valueOf(videoRepository.get("publishedRevision"));
                Map<?, ?> paymentSelection = successfulBody(client, "get_context",
                        Map.of("repositoryId", "payment-service", "selector", Map.of("kind", "CURRENT")), mapper, discovery);
                Map<?, ?> videoSelection = successfulBody(client, "get_context",
                        Map.of("repositoryId", "video-service", "selector", Map.of("kind", "CURRENT")), mapper, discovery);
                assertThat(paymentSelection.get("state")).isEqualTo("READY");
                assertThat(videoSelection.get("state")).isEqualTo("READY");
                Map<?, ?> paymentContext = mapValue(paymentSelection, "context");
                Map<?, ?> videoContext = mapValue(videoSelection, "context");
                assertThat(paymentContext.get("revision")).isEqualTo(paymentRevision);
                assertThat(videoContext.get("revision")).isEqualTo(videoRevision);
                Map<?, ?> coverage = mapValue(mapValue(paymentSelection, "overview"), "coverage");
                assertThat(coverage.get("scope")).isEqualTo("GENERATION");
                assertThat(((Number) coverage.get("extractionIssues")).longValue()).isZero();
                discovery.print();

                JourneyRecorder paymentJourney = new JourneyRecorder("payment-search-source");
                Map<?, ?> paymentSearch = successfulBody(client, "search_code", Map.of(
                        "context", paymentContext, "query", "paymentMethods",
                        "kinds", List.of("METHOD")), mapper, paymentJourney);
                Map<?, ?> paymentMethod = programElementAt(paymentSearch, "items", "src/main/java/com/example/payment/PaymentQueryController.java", "METHOD");
                assertThat(paymentMethod.get("displayName")).isEqualTo("paymentMethods");
                String paymentFactId = String.valueOf(paymentMethod.get("factId"));
                Map<?, ?> paymentSource = successfulBody(client, "read_source", Map.of(
                        "context", paymentContext, "target", factTarget(paymentFactId)), mapper, paymentJourney);
                assertFactSource(paymentSource, paymentFactId, "src/main/java/com/example/payment/PaymentQueryController.java", 13, 16, "paymentMethods");
                paymentJourney.print();

                JourneyRecorder videoJourney = new JourneyRecorder("video-route-handler-callee-implementation-source");
                Map<?, ?> routeResult = successfulBody(client, "list_entry_points", Map.of(
                        "context", videoContext, "kind", "HTTP", "httpMethod", "POST", "path", "/videos"),
                        mapper, videoJourney);
                Map<?, ?> handler = mapValue(itemAt(routeResult, "items", "list_entry_points"), "handler");
                assertThat(handler.get("displayName")).isEqualTo("upload");
                String handlerFactId = String.valueOf(handler.get("factId"));
                Map<?, ?> callees = successfulBody(client, "find_relations", Map.of(
                        "context", videoContext, "relation", "CALLEES", "factId", handlerFactId), mapper, videoJourney);
                Map<?, ?> videoServiceCallee = relationItemAt(callees, "target", "src/main/java/com/example/video/VideoService.java");
                Map<?, ?> callee = mapValue(mapValue(videoServiceCallee, "target"), "fact");
                assertThat(callee.get("displayName")).isEqualTo("upload");
                String videoServiceFactId = String.valueOf(callee.get("factId"));
                Map<?, ?> implementations = successfulBody(client, "find_relations", Map.of(
                        "context", videoContext, "relation", "IMPLEMENTATIONS", "factId", videoServiceFactId), mapper, videoJourney);
                Map<?, ?> implementationItem = relationItemAt(implementations, "origin", "src/main/java/com/example/video/DefaultVideoService.java");
                assertThat(implementationItem.get("relationKind")).isEqualTo("OVERRIDES");
                Map<?, ?> implementation = mapValue(implementationItem, "origin");
                String implementationFactId = String.valueOf(implementation.get("factId"));
                Map<?, ?> implementationSource = successfulBody(client, "read_source", Map.of(
                        "context", videoContext, "target", factTarget(implementationFactId)), mapper, videoJourney);
                assertFactSource(implementationSource, implementationFactId, "src/main/java/com/example/video/DefaultVideoService.java", 7, 11, "catalog.createTranscodingJob");
                videoJourney.print();

                JourneyRecorder emptyAndRecovery = new JourneyRecorder("empty-search-and-revision-recovery");
                Map<?, ?> emptySearch = successfulBody(client, "search_code", Map.of(
                        "context", paymentContext, "query", "unindexedFixtureToken"), mapper, emptyAndRecovery);
                assertThat(items(emptySearch, "search_code")).isEmpty();
                McpSchema.CallToolResult outdatedResult = client.callTool(McpSchema.CallToolRequest.builder("search_code").arguments(Map.of(
                        "context", currentContext("payment-service", "0".repeat(40)), "query", "paymentMethods")).build());
                assertThat(outdatedResult.isError()).isTrue();
                Map<?, ?> outdated = mapper.convertValue(outdatedResult.structuredContent(), Map.class);
                emptyAndRecovery.record(outdated, mapper);
                assertThat(outdated.get("code")).isEqualTo("REVISION_OUTDATED");
                String currentRevision = String.valueOf(outdated.get("currentRevision"));
                assertThat(currentRevision).isEqualTo(paymentRevision);
                Map<?, ?> recoveredSearch = successfulBody(client, "search_code", Map.of(
                        "context", currentContext("payment-service", currentRevision), "query", "paymentMethods", "kinds", List.of("METHOD")), mapper, emptyAndRecovery);
                String recoveredFactId = String.valueOf(programElementAt(recoveredSearch, "items",
                        "src/main/java/com/example/payment/PaymentQueryController.java", "METHOD").get("factId"));
                Map<?, ?> recoveredSource = successfulBody(client, "read_source", Map.of(
                        "context", currentContext("payment-service", currentRevision), "target", factTarget(recoveredFactId)), mapper, emptyAndRecovery);
                assertFactSource(recoveredSource, recoveredFactId, "src/main/java/com/example/payment/PaymentQueryController.java", 13, 16, "paymentMethods");
                emptyAndRecovery.print();
            }
        }
    }

    private static Map<String, Object> currentContext(String repository, String revision) {
        return Map.of("kind", "CURRENT", "repositoryId", repository, "revision", revision);
    }

    private static Map<String, Object> factTarget(String factId) { return Map.of("kind", "FACT", "factId", factId); }

    private static void assertFactSource(Map<?, ?> result, String factId, String path, int startLine, int endLine, String codeToken) {
        assertThat(mapValue(result, "target").get("factId")).isEqualTo(factId);
        assertThat(result.get("path")).isEqualTo(path);
        assertThat(String.valueOf(result.get("content"))).contains(codeToken);
        Map<?, ?> factRange = mapValue(result, "factRange");
        assertThat(mapValue(factRange, "start").get("line")).isEqualTo(startLine - 1);
        assertThat(mapValue(factRange, "end").get("line")).isEqualTo(endLine - 1);
    }

    private static Map<?, ?> successfulBody(McpSyncClient client, String toolName, Map<String, Object> arguments, JsonMapper mapper,
                                              JourneyRecorder journey) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
        assertThat(result.isError()).as("%s must succeed: %s", toolName, result.structuredContent()).isFalse();
        Map<?, ?> body = mapper.convertValue(result.structuredContent(), Map.class);
        assertThat(mapper.readTree(((McpSchema.TextContent) result.content().getFirst()).text()))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(body)));
        journey.record(body, mapper);
        return body;
    }

    private static Map<?, ?> repository(Map<?, ?> repositories, String repositoryId) {
        return items(repositories, "list_repositories").stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(item -> repositoryId.equals(item.get("repositoryId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("published fixture repository is not visible: " + repositoryId));
    }

    private static Map<?, ?> programElementAt(Map<?, ?> result, String itemKey, String sourcePath, String kind) {
        return items(result, itemKey).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(item -> sourcePath.equals(item.get("path")))
                .filter(item -> kind.equals(item.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(itemKey + " did not return " + kind + " at " + sourcePath));
    }

    private static Map<?, ?> relationItemAt(Map<?, ?> result, String programElementKey, String sourcePath) {
        return items(result, programElementKey).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(item -> sourcePath.equals((programElementKey.equals("target")
                        ? mapValue(mapValue(item, "target"), "fact") : mapValue(item, programElementKey)).get("path")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(programElementKey + " did not return " + sourcePath));
    }

    private static Map<?, ?> itemAt(Map<?, ?> result, String itemKey, String toolName) {
        return items(result, toolName).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(toolName + " returned no " + itemKey));
    }

    private static List<?> items(Map<?, ?> result, String toolName) {
        Object value = result.get("items");
        assertThat(value).as("%s items", toolName).isInstanceOf(List.class);
        return (List<?>) value;
    }

    private static Map<?, ?> mapValue(Map<?, ?> value, String field) {
        Object nested = value.get(field);
        assertThat(nested).as("%s must be an object", field).isInstanceOf(Map.class);
        return (Map<?, ?>) nested;
    }


    private static final class JourneyRecorder {
        private final String name;
        private final long startedAtNanos;
        private int toolCalls;
        private long responseBytes;

        private JourneyRecorder(String name) {
            this.name = name;
            this.startedAtNanos = System.nanoTime();
        }

        private void record(Map<?, ?> body, JsonMapper mapper) {
            responseBytes += mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8).length;
            toolCalls++;
        }

        private void print() {
            long elapsedMilliseconds = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
            System.out.printf("MCP fixture journey %s: toolCalls=%d structuredBodyUtf8Bytes=%d elapsedMillis=%d%n",
                    name, toolCalls, responseBytes, elapsedMilliseconds);
        }
    }

    private static void publish(org.springframework.data.mongodb.core.MongoTemplate template, String repositoryId,
                                String revision, String generationId, Path repositoryRoot) {
        RepositoryId id = new RepositoryId(repositoryId);
        GenerationId generation = new GenerationId(generationId);
        GenerationWriteContext lease = new GenerationWriteContext(id, generation, generationId + "-job");
        FullIndexPlan plan = new FullIndexPlanner().plan(repositoryRoot, List.of(repositoryRoot.resolve("src")));
        IndexJob job = new IndexJob(new IndexJobId(generationId + "-job"), id,
                Optional.of(new IndexJobTarget(new RepositoryRevision(revision), generation, 1L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, IndexJobOperation.BUILD);
        SourceSnapshotPublication source = new SourceSnapshotPublication(
                new JGitRepositoryAdapter(new RepositoryProperties(), JdtLsTestProperties.linuxUid()),
                new GitEvidencePublicationStore(template), new RepositoryProperties().getGitEvidenceFileTextBytes());
        SourceSnapshotPublication.PublishedSource published = source.publish(job, repositoryRoot,
                new RepositoryRevision(revision), plan, Optional.empty());
        MongoGenerationWriter generationWriter = new MongoGenerationWriter(template);
        MongoGenerationWriter.SourceOverview overview = generationWriter.sourceOverview(lease,
                published.policy().includedRoots(), published.excludedOrUnsupported());
        generationWriter.recordSourceMembership(lease, published.snapshot(), published.guide(),
                published.policy(), overview.coverage(), overview.structure());
        GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease,
                new RepositoryRevision(revision), new RepositoryRevision(revision), plan);

        assertThat(result.valid()).as("validation issues: %s", result.issues()).isTrue();
        new GenerationValidator(template).recordValid(lease, result);
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        writer.seal(lease, result.identityDigest().value());
        new MongoPublicationWriter(template).publish(new PublishGenerationCommand(id, new RepositoryRevision(revision), generation,
                generationId + "-job", java.util.Optional.empty(), new ManifestDigest(result.identityDigest().value())));
        assertThat(new MongoIndexJobStore(template).complete(job.id())).isTrue();
    }

    private List<SourceIndexBatch> exportAndPersist(DefaultJdtWorkspaceManager manager,
                                                     org.springframework.data.mongodb.core.MongoTemplate template, Path repositoryRoot,
                                                     String repositoryName, String revisionValue, String generationValue) throws IOException {
        RepositoryId repositoryId = new RepositoryId(repositoryName);
        RepositoryRevision revision = new RepositoryRevision(revisionValue);
        GenerationId generationId = new GenerationId(generationValue);
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, repositoryRoot, revision);
        FullIndexPlan plan = new FullIndexPlanner().plan(repositoryRoot, List.of(repositoryRoot.resolve("src")));
        GenerationWriteContext lease = new GenerationWriteContext(repositoryId, generationId, generationValue + "-job");
        try (TestPreparedAnalysis preparedAnalysis = TestPreparedAnalysis.forSession(snapshot, plan,
                new Lsp4jJavaSemanticService(snapshot, manager.getOrStart(snapshot)))) {
            RepositoryIndexExport export = JdtLsRepositoryIndexExporter.production().export(lease, preparedAnalysis);
            seedWritableGeneration(template, repositoryId, revision, generationId);
            MongoGenerationWriter generationWriter = new MongoGenerationWriter(template);
            generationWriter.recordAnalysis(lease, preparedAnalysis.fingerprint(), export.analysisEvidence());
            MongoIndexBatchWriter writer = new MongoIndexBatchWriter(generationWriter, lease,
                    new SourceIndexBatchDocumentMapper(template.getConverter()));
            export.batches().forEach(writer::write);
            return export.batches();
        }
    }

    private static void seedWritableGeneration(org.springframework.data.mongodb.core.MongoTemplate template,
                                               RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId) {
        String jobId = generationId.value() + "-job";
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", jobId)
                .append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION)
                .append("repoId", repositoryId.value()).append("target", new Document("revision", revision.value())
                        .append("generationId", generationId.value()).append("generation", 1L)).append("operation", "BUILD")
                .append("phase", "RUNNING").append("active", true).append("outstandingBatches", List.of()).append("acknowledgedBatches", List.of())
                .append("failedOrAmbiguousBatches", List.of()));
        new MongoGenerationWriter(template).insertManifest(new GenerationWriteContext(repositoryId, generationId, jobId),
                new Document("repoId", repositoryId.value()).append("generationId", generationId.value()).append("ownerJobId", jobId)
                .append("sourceRevision", revision.value()).append("writeState", GenerationWriteState.WRITING.name()).append("writeEpoch", 0L)
                .append("schemaVersion", com.java.semantic.model.index.IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", com.java.semantic.model.index.IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
    }

    private DefaultJdtWorkspaceManager manager(Path home) {
        JdtLsProperties properties = new JdtLsProperties(true, home, temporaryDirectory.resolve("workspace"),
                Duration.ofSeconds(180), Duration.ofSeconds(600), Duration.ofSeconds(60), 1,
                Duration.ofMinutes(30), Duration.ofMinutes(1), "2g");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        JdtWorkspaceLifecycleMetrics metrics = new JdtWorkspaceLifecycleMetrics(registry);
        return new DefaultJdtWorkspaceManager(new JdtLsProcessFactory(properties), new JdtLsReadinessProbe(properties),
                properties, registry, System::nanoTime, metrics);
    }

    private static Path copyFixture(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relativePath = source.relativize(path);
                if (relativePath.startsWith(Path.of("target"))) {
                    continue;
                }
                Path destination = target.resolve(relativePath.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
        return target;
    }
    private static String commitFixture(Path root) throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(root);
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            return git.commit().setMessage("fixture source")
                    .setAuthor("Fixture", "fixture@example.test")
                    .setCommitter("Fixture", "fixture@example.test").call().name();
        }
    }


    private static void applyPatch(Path fixtureRoot, Path patch) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "apply", "--check", patch.toString())
                .directory(fixtureRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String checkOutput = new String(process.getInputStream().readAllBytes());
        int checkExit = process.waitFor();
        if (checkExit != 0) {
            throw new AssertionError("payment-service-v2.patch cannot be applied: " + checkOutput);
        }
        Process apply = new ProcessBuilder("git", "apply", patch.toString())
                .directory(fixtureRoot.toFile())
                .redirectErrorStream(true)
                .start();
        String applyOutput = new String(apply.getInputStream().readAllBytes());
        int applyExit = apply.waitFor();
        if (applyExit != 0) {
            throw new AssertionError("payment-service-v2.patch cannot be applied: " + applyOutput);
        }
    }
}
