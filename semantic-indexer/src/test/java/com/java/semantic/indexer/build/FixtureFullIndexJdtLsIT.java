package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.GenerationWriteState;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.adapter.jdtls.DefaultJdtWorkspaceManager;
import com.java.semantic.semantic.adapter.jdtls.JdtLsHomeRequirement;
import com.java.semantic.semantic.adapter.jdtls.JdtLsProcessFactory;
import com.java.semantic.semantic.adapter.jdtls.JdtLsReadinessProbe;
import com.java.semantic.semantic.adapter.jdtls.JdtWorkspaceLifecycleMetrics;
import com.java.semantic.semantic.adapter.jdtls.Lsp4jJavaSemanticService;
import com.java.semantic.query.SemanticQueryApplication;
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
    private static final List<String> TOOL_NAMES = List.of(
            "list_repositories", "get_repository", "search_code", "get_fact_source", "list_entry_points", "find_api_routes",
            "find_event_listeners", "list_type_members", "find_method_implementations", "find_references", "find_callers", "find_callees");

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
            throws IOException {
        Path jdtLsHome = JdtLsHomeRequirement.requireHome(System.getenv("JDTLS_HOME"));
        DefaultJdtWorkspaceManager manager = manager(jdtLsHome);
        try (GenericContainer<?> mongo = new GenericContainer<>(DockerImageName.parse("mongo:8.0.4")).withExposedPorts(27017)) {
            mongo.start();
            org.springframework.data.mongodb.core.MongoTemplate template = new org.springframework.data.mongodb.core.MongoTemplate(
                    com.mongodb.client.MongoClients.create("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)), "fixture_index");
            new IndexSchemaBootstrap(template).bootstrap();
            List<SourceIndexBatch> payment = exportAndPersist(manager, template, PAYMENT_FIXTURE, "payment-service", "a".repeat(40), "payment-generation");
            List<SourceIndexBatch> video = exportAndPersist(manager, template, VIDEO_FIXTURE, "video-service", "b".repeat(40), "video-generation");
            List<SourceIndexBatch> order = exportAndPersist(manager, template, ORDER_FIXTURE, "order-service", "c".repeat(40), "order-generation");
            publish(template, "payment-service", "a".repeat(40), "payment-generation");
            publish(template, "video-service", "b".repeat(40), "video-generation");
            publish(template, "order-service", "c".repeat(40), "order-generation");

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
                McpSchema.ListToolsResult tools = client.listTools();
                assertThat(tools.tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
                assertThat(tools.tools()).allSatisfy(tool -> {
                    assertThat(tool.inputSchema()).isNotEmpty();
                    assertThat(tool.outputSchema()).isNotEmpty();
                    assertThat(schemaProperties(tool.outputSchema())).isNotEmpty();
                });
                McpSchema.Tool searchTool = tools.tools().stream().filter(tool -> tool.name().equals("search_code")).findFirst().orElseThrow();
                assertThat(schemaProperties(searchTool.outputSchema()).containsKey("sourceCoverage")).isTrue();

                JourneyRecorder discovery = new JourneyRecorder("fixture-discovery");
                Map<?, ?> repositories = successfulBody(client, "list_repositories", Map.of(), mapper, discovery);
                Map<?, ?> paymentRepository = repository(repositories, "payment-service");
                Map<?, ?> videoRepository = repository(repositories, "video-service");
                String paymentRevision = String.valueOf(paymentRepository.get("revision"));
                String videoRevision = String.valueOf(videoRepository.get("revision"));
                discovery.print();

                JourneyRecorder paymentJourney = new JourneyRecorder("payment-search-source");
                Map<?, ?> paymentSearch = successfulBody(client, "search_code", Map.of(
                        "repositoryId", "payment-service", "revision", paymentRevision, "query", "paymentMethods",
                        "kinds", List.of("METHOD")), mapper, paymentJourney);
                assertCoverageWithoutIssues(paymentSearch);
                Map<?, ?> paymentMethod = programElementAt(paymentSearch, "items", "src/main/java/com/example/payment/PaymentQueryController.java",
                        "METHOD");
                assertThat(paymentMethod.get("kind")).isEqualTo("METHOD");
                assertThat(String.valueOf(paymentMethod.get("displayName"))).contains("PaymentQueryController", "paymentMethods");
                assertSource(source(paymentMethod), "src/main/java/com/example/payment/PaymentQueryController.java", 13, 16, "paymentMethods");
                String paymentFactId = String.valueOf(paymentMethod.get("factId"));
                Map<?, ?> paymentSource = successfulBody(client, "get_fact_source", Map.of(
                        "repositoryId", "payment-service", "revision", paymentRevision, "factId", paymentFactId), mapper, paymentJourney);
                assertFactSource(paymentSource, paymentFactId, "src/main/java/com/example/payment/PaymentQueryController.java", 13, 16,
                        "paymentMethods");
                paymentJourney.print();

                JourneyRecorder videoJourney = new JourneyRecorder("video-route-handler-callee-implementation-source");
                Map<?, ?> routeResult = successfulBody(client, "find_api_routes", Map.of(
                        "repositoryId", "video-service", "revision", videoRevision, "httpMethod", "POST", "path", "/videos"), mapper,
                        videoJourney);
                Map<?, ?> route = itemAt(routeResult, "items", "find_api_routes");
                Map<?, ?> handler = mapValue(route, "handler");
                assertThat(String.valueOf(handler.get("displayName"))).contains("VideoController", "upload");
                assertSource(source(handler), "src/main/java/com/example/video/VideoController.java", 10, 13, "videoService.upload");
                String handlerFactId = String.valueOf(handler.get("factId"));
                Map<?, ?> callees = successfulBody(client, "find_callees", Map.of(
                        "repositoryId", "video-service", "revision", videoRevision, "methodFactId", handlerFactId), mapper, videoJourney);
                Map<?, ?> videoServiceCallee = relationItemAt(callees, "callee", "src/main/java/com/example/video/VideoService.java");
                Map<?, ?> callee = mapValue(videoServiceCallee, "callee");
                assertThat(String.valueOf(callee.get("displayName"))).contains("VideoService", "upload");
                assertSource(source(callee), "src/main/java/com/example/video/VideoService.java", 4, 4, "upload");
                String videoServiceFactId = String.valueOf(callee.get("factId"));
                Map<?, ?> implementations = successfulBody(client, "find_method_implementations", Map.of(
                        "repositoryId", "video-service", "revision", videoRevision, "methodFactId", videoServiceFactId), mapper, videoJourney);
                Map<?, ?> implementationItem = relationItemAt(implementations, "implementation",
                        "src/main/java/com/example/video/DefaultVideoService.java");
                assertThat(implementationItem.get("relationKind")).isEqualTo("OVERRIDES");
                Map<?, ?> implementation = mapValue(implementationItem, "implementation");
                assertThat(String.valueOf(implementation.get("displayName"))).contains("DefaultVideoService", "upload");
                assertSource(source(implementation), "src/main/java/com/example/video/DefaultVideoService.java", 7, 11,
                        "catalog.createTranscodingJob");
                String implementationFactId = String.valueOf(implementation.get("factId"));
                Map<?, ?> implementationSource = successfulBody(client, "get_fact_source", Map.of(
                        "repositoryId", "video-service", "revision", videoRevision, "factId", implementationFactId), mapper, videoJourney);
                assertFactSource(implementationSource, implementationFactId, "src/main/java/com/example/video/DefaultVideoService.java", 7, 11,
                        "catalog.createTranscodingJob");
                videoJourney.print();

                JourneyRecorder emptyAndRecovery = new JourneyRecorder("empty-search-and-revision-recovery");
                Map<?, ?> emptySearch = successfulBody(client, "search_code", Map.of(
                        "repositoryId", "payment-service", "revision", paymentRevision, "query", "unindexedFixtureToken"), mapper,
                        emptyAndRecovery);
                assertThat(items(emptySearch, "search_code")).isEmpty();
                assertCoverageWithoutIssues(emptySearch);
                McpSchema.CallToolResult outdatedResult = client.callTool(new McpSchema.CallToolRequest("search_code", Map.of(
                        "repositoryId", "payment-service", "revision", "0".repeat(40), "query", "paymentMethods")));
                assertThat(outdatedResult.isError()).isTrue();
                Map<?, ?> outdated = mapper.convertValue(outdatedResult.structuredContent(), Map.class);
                emptyAndRecovery.record(outdated, mapper);
                assertThat(outdated.get("code")).isEqualTo("REVISION_OUTDATED");
                String currentRevision = String.valueOf(outdated.get("currentRevision"));
                assertThat(currentRevision).isEqualTo(paymentRevision);
                Map<?, ?> recoveredSearch = successfulBody(client, "search_code", Map.of(
                        "repositoryId", "payment-service", "revision", currentRevision, "query", "paymentMethods",
                        "kinds", List.of("METHOD")), mapper, emptyAndRecovery);
                Map<?, ?> recoveredPaymentMethod = programElementAt(recoveredSearch, "items",
                        "src/main/java/com/example/payment/PaymentQueryController.java", "METHOD");
                String recoveredFactId = String.valueOf(recoveredPaymentMethod.get("factId"));
                Map<?, ?> recoveredSource = successfulBody(client, "get_fact_source", Map.of(
                        "repositoryId", "payment-service", "revision", currentRevision, "factId", recoveredFactId), mapper,
                        emptyAndRecovery);
                assertFactSource(recoveredSource, recoveredFactId, "src/main/java/com/example/payment/PaymentQueryController.java", 13, 16,
                        "paymentMethods");
                emptyAndRecovery.print();
            }
        }
    }

    private static void assertCoverageWithoutIssues(Map<?, ?> result) {
        Map<?, ?> coverage = mapValue(result, "sourceCoverage");
        assertThat(coverage.get("indexedSourceCount")).isInstanceOf(Number.class);
        assertThat(((Number) coverage.get("indexedSourceCount")).longValue()).isPositive();
        assertThat(coverage.get("issueCount")).isEqualTo(0);
        assertThat(coverage.get("issueCodes")).isEqualTo(List.of());
    }

    private static void assertFactSource(Map<?, ?> result, String factId, String path, int startLine, int endLine, String codeToken) {
        assertThat(result.get("factId")).isEqualTo(factId);
        assertSource(mapValue(result, "source"), path, startLine, endLine, codeToken);
        Map<?, ?> factRange = mapValue(result, "factRange");
        assertThat(factRange.get("startLine")).isEqualTo(startLine);
        assertThat(factRange.get("endLine")).isEqualTo(endLine);
    }

    private static void assertSource(Map<?, ?> source, String path, int startLine, int endLine, String codeToken) {
        assertThat(source.get("path")).isEqualTo(path);
        assertThat(source.get("startLine")).isEqualTo(startLine);
        assertThat(source.get("endLine")).isEqualTo(endLine);
        assertThat(String.valueOf(source.get("code"))).contains(codeToken);
    }

    private static Map<?, ?> successfulBody(McpSyncClient client, String toolName, Map<String, Object> arguments, JsonMapper mapper,
                                              JourneyRecorder journey) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        assertThat(result.isError()).as("%s must succeed", toolName).isFalse();
        Map<?, ?> body = mapper.convertValue(result.structuredContent(), Map.class);
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
                .filter(item -> sourcePath.equals(source(item).get("path")))
                .filter(item -> kind.equals(item.get("kind")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(itemKey + " did not return " + kind + " at " + sourcePath));
    }

    private static Map<?, ?> relationItemAt(Map<?, ?> result, String programElementKey, String sourcePath) {
        return items(result, programElementKey).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(item -> sourcePath.equals(source(mapValue(item, programElementKey)).get("path")))
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

    private static Map<?, ?> source(Map<?, ?> programElement) {
        return mapValue(programElement, "source");
    }

    private static Map<?, ?> schemaProperties(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        assertThat(properties).isInstanceOf(Map.class);
        return (Map<?, ?>) properties;
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
                                String revision, String generationId) {
        RepositoryId id = new RepositoryId(repositoryId);
        GenerationId generation = new GenerationId(generationId);
        GenerationWriteContext lease = new GenerationWriteContext(id, generation, generationId + "-job");
        GenerationValidator.ValidationResult result = new GenerationValidator(template).validate(lease,
                new RepositoryRevision(revision), new RepositoryRevision(revision));

        assertThat(result.valid()).as("validation issues: %s", result.issues()).isTrue();
        new GenerationValidator(template).recordValid(lease, result);
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        writer.seal(lease, result.identityDigest().value());
        new MongoPublicationWriter(template).publish(new PublishGenerationCommand(id, new RepositoryRevision(revision), generation,
                generationId + "-job", java.util.Optional.empty(), new ManifestDigest(result.identityDigest().value())));
    }

    private List<SourceIndexBatch> exportAndPersist(DefaultJdtWorkspaceManager manager,
                                                     org.springframework.data.mongodb.core.MongoTemplate template, Path fixture,
                                                     String repositoryName, String revisionValue, String generationValue) throws IOException {
        Path repositoryRoot = copyFixture(fixture, temporaryDirectory.resolve(repositoryName));
        RepositoryId repositoryId = new RepositoryId(repositoryName);
        RepositoryRevision revision = new RepositoryRevision(revisionValue);
        GenerationId generationId = new GenerationId(generationValue);
        manager.getOrStart(new RepositorySnapshot(repositoryId, repositoryRoot, revision));
        List<SourceIndexBatch> batches = new JdtLsRepositoryIndexExporter(new Lsp4jJavaSemanticService(manager)).export(repositoryId, revision, generationId,
                new FullIndexPlanner().plan(repositoryRoot));
        seedWritableGeneration(template, repositoryId, revision, generationId);
        GenerationWriteContext lease = new GenerationWriteContext(repositoryId, generationId, generationValue + "-job");
        MongoIndexBatchWriter writer = new MongoIndexBatchWriter(new MongoGenerationWriter(template), lease,
                new SourceIndexBatchDocumentMapper(template.getConverter()));
        batches.forEach(writer::write);
        return batches;
    }

    private static void seedWritableGeneration(org.springframework.data.mongodb.core.MongoTemplate template,
                                               RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId) {
        String jobId = generationId.value() + "-job";
        template.getCollection(IndexCollections.INDEX_JOBS).insertOne(new Document("jobId", jobId)
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
