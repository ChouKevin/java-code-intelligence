package com.java.semantic.indexer.api;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.application.IndexerPreparationFacade;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.job.GitEvidenceJobHandler;
import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewBaselineRule;
import java.util.Optional;
import com.java.semantic.indexer.mcp.IndexerMcpToolCatalogConfiguration;
import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Native Mongo admission plus real HTTP and SDK wire; no dispatcher or JDT is needed for admission. */
@Tag("mongo-it")
class IndexerPreparationTransportIT {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void sdk_discovery_auth_and_lost_response_recovery_match_http_for_all_preparations() throws Exception {
        Path remote = directory.resolve("remote");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(remote.toFile()).call();
             MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.4")) {
            Files.writeString(remote.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>fixture</artifactId><version>1</version></project>");
            git.add().addFilepattern(".").call();
            String revision = git.commit().setMessage("fixture").setAuthor("Fixture", "fixture@example.test").call().getId().name();
            mongo.start();
            Files.createDirectories(directory.resolve("checkouts"));
            SpringApplication application = new SpringApplication(NativeApplication.class);
            application.setDefaultProperties(Map.of("server.port", "0", "server.address", "127.0.0.1",
                    "spring.mongodb.uri", mongo.getConnectionString() + "/preparation_transport",
                    "semantic.indexer.admin-token", "admin-token", "semantic.data-root", directory.resolve("checkouts").toString(),
                    "semantic.repositories.orders.url", remote.toUri().toString(), "semantic.repositories.orders.default-branch", "main",
                    "semantic.repositories.other.url", remote.toUri().toString(), "semantic.repositories.other.default-branch", "main"));
            try (ConfigurableApplicationContext context = application.run(
                    "--spring.mongodb.uri=" + mongo.getConnectionString() + "/preparation_transport",
                    "--semantic.indexer.admin-token=admin-token",
                    "--semantic.data-root=" + directory.resolve("checkouts"),
                    "--semantic.jdtls.isolation-mode=LOCAL_TRUSTED")) {
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                String base = "http://127.0.0.1:" + port;
                MongoIndexJobStore store = context.getBean(MongoIndexJobStore.class);
                assertThat(http(base, "/mcp", "POST", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"), "query-token").statusCode()).isEqualTo(401);
                assertThat(http(base, "/index/repositories/orders/codebase", "POST", Map.of("requestId", UUID.randomUUID().toString()), "query-token").statusCode()).isEqualTo(401);
                try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(base + "/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(mapper))
                        .httpRequestCustomizer((request, method, uri, body, callContext) -> request.header("X-Api-Token", "admin-token")).build())
                        .requestTimeout(Duration.ofSeconds(20)).build()) {
                    client.initialize();
                    assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                            .containsExactlyInAnyOrder("refresh_repository_metadata", "prepare_codebase", "prepare_review", "get_job");
                    for (McpSchema.Tool tool : client.listTools().tools()) {
                        boolean lookup = tool.name().equals("get_job");
                        assertThat(tool.annotations().readOnlyHint()).isEqualTo(lookup);
                        assertThat(tool.annotations().idempotentHint()).isEqualTo(lookup);
                        assertThat(tool.annotations().destructiveHint()).isFalse();
                        assertThat(tool.outputSchema()).containsKey("oneOf");
                    }
                    List<String> operations = List.of("prepare_codebase", "refresh_repository_metadata", "prepare_review");
                    for (String operation : operations) {
                        String requestId = UUID.randomUUID().toString();
                        Map<String, Object> fields = operation.equals("prepare_review")
                                ? Map.of("repositoryId", "orders", "requestId", requestId, "selection", Map.of("kind", "COMMIT", "revision", revision))
                                : Map.of("repositoryId", "orders", "requestId", requestId);
                        McpSchema.CallToolResult accepted = client.callTool(McpSchema.CallToolRequest.builder(operation).arguments(fields).build());
                        JsonNode acceptedJson = applicationJson(accepted);
                        assertThat(accepted.isError()).isFalse();
                        String originalJobId = acceptedJson.get("jobId").asString();
                        assertThat(acceptedJson.get("requestId").asString()).isEqualTo(requestId);
                        HttpResponse<String> admittedHttp = http(base, "/index/repositories/orders/jobs?requestId=" + requestId,
                                "GET", Map.of(), "admin-token");
                        assertThat(admittedHttp.statusCode()).isEqualTo(200);
                        assertThat(mapper.readTree(admittedHttp.body())).isEqualTo(acceptedJson);
                        if (operation.equals("prepare_codebase")) {
                            assertThat(acceptedJson.get("preparationBranch").asString()).isEqualTo("main");
                            assertThat(acceptedJson.get("target").get("revision").asString()).isEqualTo(revision);
                        }
                        if (operation.equals("refresh_repository_metadata")) {
                            assertThat(acceptedJson.get("branch").asString()).isEqualTo("main");
                            assertThat(acceptedJson.get("requested").has("branch")).isFalse();
                        }
                        IndexJob running = store.startNextAccepted().orElseThrow();
                        assertThat(running.id().value()).isEqualTo(originalJobId);
                        if (operation.equals("prepare_review")) {
                            store.resolveReviewEndpoints(running.id(), new ResolvedReviewEndpoints(Optional.empty(),
                                    RepositoryRevision.ofSha(revision), ReviewBaselineRule.EMPTY_TREE));
                        }
                        assertThat(store.fail(running.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isTrue();
                        String laterRequest = UUID.randomUUID().toString();
                        HttpResponse<String> later = http(base, "/index/repositories/orders/metadata", "POST", Map.of("requestId", laterRequest), "admin-token");
                        assertThat(later.statusCode()).isEqualTo(202);
                        String laterJob = mapper.readTree(later.body()).get("jobId").asString();
                        assertThat(later.headers().firstValue("Location")).contains("/index/repositories/orders/jobs?jobId=" + laterJob);
                        JsonNode laterMcp = applicationJson(client.callTool(McpSchema.CallToolRequest.builder("get_job")
                                .arguments(Map.of("repositoryId", "orders", "jobId", laterJob)).build()));
                        assertThat(mapper.readTree(later.body())).isEqualTo(laterMcp);
                        JsonNode recovered = applicationJson(client.callTool(McpSchema.CallToolRequest.builder("get_job")
                                .arguments(Map.of("repositoryId", "orders", "requestId", requestId)).build()));
                        HttpResponse<String> httpRecovery = http(base, "/index/repositories/orders/jobs?requestId=" + requestId, "GET", Map.of(), "admin-token");
                        assertThat(httpRecovery.statusCode()).isEqualTo(200);
                        assertThat(mapper.readTree(httpRecovery.body())).isEqualTo(recovered);
                        JsonNode byJob = applicationJson(client.callTool(McpSchema.CallToolRequest.builder("get_job")
                                .arguments(Map.of("repositoryId", "orders", "jobId", originalJobId)).build()));
                        assertThat(byJob).isEqualTo(recovered);
                        if (operation.equals("prepare_review")) {
                            assertThat(recovered.get("review").get("resolvedEndpoints").get("afterRevision").asString()).isEqualTo(revision);
                            assertThat(recovered.get("review").get("resolvedEndpoints").get("baselineRule").asString()).isEqualTo("EMPTY_TREE");
                            assertThat(recovered.get("review").get("resolvedEndpoints").has("beforeRevision")).isFalse();
                        }
                        assertThat(recovered.get("jobId").asString()).isEqualTo(originalJobId).isNotEqualTo(laterJob);
                        assertThat(recovered.get("phase").asString()).isEqualTo("FAILED");
                        assertThat(recovered.get("failureCategory").asString()).isEqualTo("WORKER_INTERRUPTED");
                        MongoIndexJobStore restarted = new MongoIndexJobStore(context.getBean(MongoTemplate.class));
                        assertThat(restarted.find(RepositoryId.of("orders"), new PreparationRequestId(requestId)).orElseThrow().id().value()).isEqualTo(originalJobId);
                        McpSchema.CallToolResult reuse = client.callTool(McpSchema.CallToolRequest.builder("refresh_repository_metadata")
                                .arguments(Map.of("repositoryId", "orders", "requestId", requestId)).build());
                        JsonNode reuseJson = applicationJson(reuse);
                        assertThat(reuse.isError()).isTrue();
                        assertThat(reuseJson.get("code").asString()).isEqualTo("REQUEST_ID_REUSED");
                        assertThat(reuseJson.get("jobId").asString()).isEqualTo(originalJobId);
                        HttpResponse<String> httpReuse = http(base, "/index/repositories/orders/metadata", "POST", Map.of("requestId", requestId), "admin-token");
                        assertThat(httpReuse.statusCode()).isEqualTo(409);
                        assertThat(mapper.readTree(httpReuse.body())).isEqualTo(reuseJson);
                        JsonNode unknown = applicationJson(client.callTool(McpSchema.CallToolRequest.builder("get_job")
                                .arguments(Map.of("repositoryId", "other", "requestId", requestId)).build()));
                        assertThat(unknown.get("code").asString()).isEqualTo("REQUEST_NOT_FOUND");
                        assertThat(unknown.get("retryable").asBoolean()).isTrue();
                        assertThat(unknown.has("jobId")).isFalse();
                        HttpResponse<String> other = http(base, "/index/repositories/other/jobs?requestId=" + requestId, "GET", Map.of(), "admin-token");
                        assertThat(other.statusCode()).isEqualTo(404);
                        assertThat(mapper.readTree(other.body())).isEqualTo(unknown);
                        HttpResponse<String> wrongRepositoryJob = http(base, "/index/repositories/other/jobs?jobId=" + originalJobId,
                                "GET", Map.of(), "admin-token");
                        assertThat(wrongRepositoryJob.statusCode()).isEqualTo(404);
                        assertThat(mapper.readTree(wrongRepositoryJob.body()).get("code").asString()).isEqualTo("JOB_NOT_FOUND");
                        assertThat(mapper.readTree(wrongRepositoryJob.body()).has("jobId")).isFalse();
                        IndexJob laterRunning = store.startNextAccepted().orElseThrow();
                        assertThat(laterRunning.id().value()).isEqualTo(laterJob);
                        assertThat(store.fail(laterRunning.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isTrue();
                    }
                    String metadataRequest = UUID.randomUUID().toString();
                    HttpResponse<String> metadataAdmission = http(base, "/index/repositories/orders/metadata", "POST",
                            Map.of("requestId", metadataRequest, "branch", "main"), "admin-token");
                    assertThat(metadataAdmission.statusCode()).isEqualTo(202);
                    IndexJob metadataJob = store.startNextAccepted().orElseThrow();
                    GitEvidencePublicationStore evidence = new GitEvidencePublicationStore(context.getBean(MongoTemplate.class));
                    // This fixture starts no analysis subprocesses, so there are none for the mutation gate to stop.
                    new GitEvidenceJobHandler(context.getBean(RepositoryRuntimeRegistry.class),
                            context.getBean(JGitRepositoryAdapter.class), evidence, repositoryId -> { }).prepare(metadataJob);
                    assertThat(evidence.metadataReady(metadataJob)).isTrue();
                    assertThat(store.complete(metadataJob.id())).isTrue();
                    JsonNode metadata = applicationJson(client.callTool(McpSchema.CallToolRequest.builder("get_job")
                            .arguments(Map.of("repositoryId", "orders", "requestId", metadataRequest)).build()));
                    HttpResponse<String> metadataHttp = http(base, "/index/repositories/orders/jobs?requestId=" + metadataRequest,
                            "GET", Map.of(), "admin-token");
                    assertThat(metadataHttp.statusCode()).isEqualTo(200);
                    assertThat(mapper.readTree(metadataHttp.body())).isEqualTo(metadata);
                    assertThat(metadata.get("phase").asString()).isEqualTo("COMPLETE");
                    JsonNode pinned = metadata.get("metadataResult");
                    assertThat(pinned.get("headRevision").asString()).isEqualTo(revision);
                    assertThat(pinned.get("historyId").isString()).isTrue();
                    assertThat(pinned.get("catalogId").isString()).isTrue();
                    assertThat(pinned.get("coverage").get("total").asLong()).isEqualTo(1L);
                    assertThat(metadata.get("requested").get("branch").asString()).isEqualTo("main");
                    Map<String, Object> invalidFields = Map.of("repositoryId", "orders", "requestId", UUID.randomUUID().toString(), "revision", revision);
                    McpSchema.CallToolResult invalid = client.callTool(McpSchema.CallToolRequest.builder("prepare_codebase").arguments(invalidFields).build());
                    assertThat(invalid.isError()).isTrue();
                    HttpResponse<String> invalidHttp = http(base, "/index/repositories/orders/codebase", "POST",
                            Map.of("requestId", invalidFields.get("requestId"), "revision", revision), "admin-token");
                    assertThat(invalidHttp.statusCode()).isEqualTo(400);
                    assertThat(mapper.readTree(invalidHttp.body())).isEqualTo(applicationJson(invalid));
                }
            }
        }
    }
    private JsonNode applicationJson(McpSchema.CallToolResult result) {
        JsonNode structured = mapper.valueToTree(result.structuredContent());
        assertThat(result.content()).hasSize(1);
        McpSchema.TextContent text = (McpSchema.TextContent) result.content().getFirst();
        assertThat(mapper.readTree(text.text())).isEqualTo(structured);
        return structured;
    }
    private HttpResponse<String> http(String base, String route, String method, Map<String, Object> body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(20))
                .header("X-Api-Token", token).header("Content-Type", "application/json");
        if (method.equals("GET")) { request.GET(); }
        else { request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))); }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({IndexerPreparationFacade.class, IndexRepositoryController.class, IndexerApiExceptionHandler.class,
            IndexerAdminSecurityConfiguration.class, IndexerMcpToolCatalogConfiguration.class,
            RepositoryRuntimeRegistry.class, JGitRepositoryAdapter.class})
    @ComponentScan(basePackages = "com.java.semantic.indexer.repository", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = RepositoryRevisionResolver.class))
    @org.springframework.boot.context.properties.EnableConfigurationProperties({RepositoryProperties.class, JdtLsProperties.class})
    static class NativeApplication {
        @Bean MongoIndexJobStore jobs(MongoTemplate template, RepositoryRuntimeRegistry registry) {
            new IndexSchemaBootstrap(template).bootstrap();
            new ConfiguredRepositoryPublisher(template, registry).publish();
            return new MongoIndexJobStore(template);
        }
        @Bean IndexRequestService requests(RepositoryRevisionResolver resolver, RepositoryRuntimeRegistry registry, MongoIndexJobStore jobs) {
            return new IndexRequestService(resolver, registry, jobs);
        }
    }
}
