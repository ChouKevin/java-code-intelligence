package com.java.semantic.indexer.api;

import com.java.semantic.indexer.build.FullIndexPlan;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.indexer.build.GenerationValidator;
import com.java.semantic.indexer.build.MongoIndexBatchWriter;
import com.java.semantic.indexer.build.SearchProjector;
import com.java.semantic.indexer.build.SourceIndexBatch;
import com.java.semantic.indexer.build.SourceIndexBatchDocumentMapper;
import com.java.semantic.indexer.build.SourceSnapshotPublication;
import com.java.semantic.indexer.build.SyntaxSymbolProjector;
import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.job.IndexFailureCategory;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.PreparationRequest;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.MongoPublicationWriter;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.SemanticQueryApplication;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Query application and SDK over Mongo prepared by Indexer, without checkout or JDT. */
@Tag("mongo-it")
class UnifiedQueryDiscoveryTransportIT {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MongoDBContainer mongo;
    private MongoClient writer;
    private MongoTemplate template;
    private ConfigurableApplicationContext query;
    private HttpClient httpClient;
    private McpSyncClient client;
    private String base;

    @BeforeEach
    void startColdQuery() {
        mongo = new MongoDBContainer("mongo:8.0.4");
        mongo.start();
        String uri = mongo.getConnectionString() + "/unified_query_discovery"
                + "?serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&socketTimeoutMS=10000";
        writer = MongoClients.create(uri);
        template = new MongoTemplate(writer, "unified_query_discovery");
        new IndexSchemaBootstrap(template).bootstrap();
        publishRegistry(Set.of("orders", "hidden", "removed"));
        publishRegistry(Set.of("orders", "hidden"));

        SpringApplication application = new SpringApplication(SemanticQueryApplication.class);
        query = application.run("--spring.config.name=unified-query-native", "--server.port=0",
                "--server.address=127.0.0.1", "--spring.mongodb.uri=" + uri,
                "--semantic.query.api-token=query-token", "--semantic.query.storage-timeout=2s",
                "--semantic.query.read-policy.forbidden-repositories[0]=hidden",
                "--semantic.query.git-evidence.allowed-repositories[0]=orders",
                "--spring.jackson.default-property-inclusion=non_absent",
                "--spring.jackson.deserialization.fail-on-unknown-properties=true",
                "--spring.ai.mcp.server.enabled=true", "--spring.ai.mcp.server.protocol=STATELESS",
                "--spring.ai.mcp.server.type=SYNC", "--spring.ai.mcp.server.stdio=false",
                "--spring.ai.mcp.server.tool-callback-converter=false",
                "--spring.ai.mcp.server.annotation-scanner.enabled=false",
                "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp",
                "--spring.ai.mcp.server.capabilities.tool=true",
                "--spring.ai.mcp.server.capabilities.resource=false",
                "--spring.ai.mcp.server.capabilities.prompt=false",
                "--spring.ai.mcp.server.capabilities.completion=false");
        int port = ((WebServerApplicationContext) query).getWebServer().getPort();
        base = "http://127.0.0.1:" + port;
        httpClient = HttpClient.newHttpClient();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder(base + "/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(mapper))
                        .httpRequestCustomizer((request, method, target, body, callContext) ->
                                request.header("X-Api-Token", "query-token")).build())
                .requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
    }

    @AfterEach
    void stopQuery() {
        if (Objects.nonNull(client)) client.close();
        if (Objects.nonNull(query)) query.close();
        if (Objects.nonNull(httpClient)) httpClient.close();
        if (Objects.nonNull(writer)) writer.close();
        if (Objects.nonNull(mongo)) mongo.stop();
    }

    @Test
    void configured_unindexed_repository_is_discoverable_but_removed_and_forbidden_repositories_are_not() throws Exception {
        HttpResponse<String> response = http("GET", "/api/v1/repositories", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode repositories = mapper.readTree(response.body());
        assertThat(repositories.at("/items/0/repositoryId").asString()).isEqualTo("orders");
        assertThat(repositories.get("items").size()).isEqualTo(1);
        assertThat(response.body()).doesNotContain("never-contacted.invalid", directory.toString());
        assertThat(applicationJson("list_repositories", Map.of(), false)).isEqualTo(repositories);
        assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "list_repositories", "get_context", "search_code", "list_files", "search_text", "read_source",
                "list_entry_points", "get_outline", "find_relations", "list_git_branches", "list_git_commits",
                "compare_revisions", "get_file_diff");

        publishRegistry(Set.of("hidden"));
        JsonNode removed = mapper.readTree(http("GET", "/api/v1/repositories", Map.of()).body());
        assertThat(removed.get("items").size()).isZero();
        assertThat(applicationJson("list_repositories", Map.of(), false)).isEqualTo(removed);
    }

    @Test
    void cold_current_separates_active_identity_and_failed_first_build_never_creates_readable_context() throws Exception {
        Map<String, Object> request = Map.of("repositoryId", "orders", "selector", Map.of("kind", "CURRENT"));
        JsonNode cold = currentContext(request);
        assertUnindexed(cold);
        assertThat(cold.get("configuredBranch").asString()).isEqualTo("main");
        assertThat(cold.has("activeJob")).isFalse();

        MongoIndexJobStore jobs = new MongoIndexJobStore(template);
        PreparationRequestId requestId = new PreparationRequestId(UUID.randomUUID().toString());
        IndexJob accepted = jobs.admitCodebase(RepositoryId.of("orders"), PreparationRequest.codebase(requestId),
                "main", RepositoryRevision.ofSha("a".repeat(40)));
        JsonNode pending = currentContext(request);
        assertUnindexed(pending);
        assertThat(pending.at("/activeJob/jobId").asString()).isEqualTo(accepted.id().value());
        assertThat(pending.at("/activeJob/requestId").asString()).isEqualTo(requestId.value());
        IndexJob running = jobs.startNextAccepted().orElseThrow();
        assertThat(jobs.fail(running.id(), IndexFailureCategory.WORKER_INTERRUPTED)).isTrue();
        JsonNode failed = currentContext(request);
        assertUnindexed(failed);
        assertThat(failed.has("activeJob")).isFalse();

        Map<String, Object> invalid = Map.of("repositoryId", "orders", "selector", Map.of("kind", "CURRENT"),
                "unexpected", true);
        HttpResponse<String> rejected = http("POST", "/api/v1/context", invalid);
        assertThat(rejected.statusCode()).isEqualTo(400);
        JsonNode error = mapper.readTree(rejected.body());
        assertThat(error.get("code").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(applicationJson("get_context", invalid, true)).isEqualTo(error);
    }

    @Test
    void published_unicode_source_continues_losslessly_over_http_and_native_mcp() throws Exception {
        String path = "src/main/java/Unicode.java";
        String comment = "//" + "😀".repeat(17_000) + "\r\n";
        String declaration = "class Unicode { String marker = \"😀needle\"; }\r\n";
        publishUnicodeSource(path, comment + declaration);
        JsonNode published = currentContext(Map.of("repositoryId", "orders", "selector", Map.of("kind", "CURRENT")));
        assertThat(published.get("state").asString()).isEqualTo("READY");
        JsonNode context = published.get("context");
        Map<String, Object> target = Map.of("kind", "FILE", "path", path, "startLine", 1);
        JsonNode first = sameHttpAndMcp("read_source", "/api/v1/source",
                Map.of("context", context, "target", target, "maxLines", 1));
        JsonNode second = sameHttpAndMcp("read_source", "/api/v1/source",
                Map.of("context", context, "target", target, "maxLines", 1, "cursor", first.get("nextCursor").asString()));
        JsonNode third = sameHttpAndMcp("read_source", "/api/v1/source",
                Map.of("context", context, "target", target, "maxLines", 1, "cursor", second.get("nextCursor").asString()));
        assertThat(first.get("content").asString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
        assertThat(first.get("endLineComplete").asBoolean()).isFalse();
        assertThat(second.get("startLineComplete").asBoolean()).isFalse();
        assertThat(first.get("content").asString() + second.get("content").asString()).isEqualTo(comment);
        assertThat(third.get("content").asString()).isEqualTo(declaration);
        assertThat(third.get("rangeComplete").asBoolean()).isTrue();
        assertThat(third.has("nextCursor")).isFalse();
        JsonNode matches = sameHttpAndMcp("search_text", "/api/v1/search-text",
                Map.of("context", context, "query", "needle"));
        assertThat(matches.at("/items/0/range/start/line").asInt()).isEqualTo(1);
        assertThat(matches.at("/items/0/range/start/character").asInt()).isEqualTo(declaration.indexOf("needle"));
        JsonNode outline = sameHttpAndMcp("get_outline", "/api/v1/outline",
                Map.of("context", context, "target", Map.of("kind", "FILE", "path", path), "kinds", List.of("FIELD")));
        assertThat(outline.get("items").size()).isEqualTo(1);
        JsonNode fact = sameHttpAndMcp("read_source", "/api/v1/source",
                Map.of("context", context, "target", Map.of("kind", "FACT", "factId", outline.at("/items/0/factId").asString())));
        assertThat(fact.get("content").asString()).isEqualTo("marker = \"😀needle\"");
        assertThat(fact.get("rangeComplete").asBoolean()).isTrue();
    }

    private void publishUnicodeSource(String path, String content) throws Exception {
        Path root = directory.resolve("unicode-source");
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), content);
        RepositoryRevision revision;
        try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            revision = RepositoryRevision.ofSha(git.commit().setMessage("Unicode source fixture")
                    .setAuthor("Test", "test@example.test").setCommitter("Test", "test@example.test").call().getId().name());
        }
        JdtLsTestProperties.prepareSafeCheckoutRoot(root);
        RepositoryId repository = RepositoryId.of("orders");
        MongoIndexJobStore jobs = new MongoIndexJobStore(template);
        jobs.admitCodebase(repository, PreparationRequest.codebase(new PreparationRequestId(UUID.randomUUID().toString())), "main", revision);
        IndexJob job = jobs.startNextAccepted().orElseThrow();
        IndexJobTarget target = job.target().orElseThrow();
        GenerationWriteContext context = new GenerationWriteContext(repository, target.generationId(), job.id().value());
        MongoGenerationWriter generations = new MongoGenerationWriter(template);
        generations.insertManifest(context, new Document("sourceRevision", revision.value())
                .append("writeState", "WRITING").append("writeEpoch", 0L).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                "e".repeat(64), "e".repeat(64), "e".repeat(64), "e".repeat(64),
                List.of(new AnalysisInputs.Project(".", "e".repeat(64), Map.of(), List.of(),
                        List.of(new AnalysisInputs.Root("src/main/java", "MAIN", true, List.of())), List.of(), List.of())));
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        generations.recordAnalysis(context, fingerprint, new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                fingerprint.digest(), "SUCCESS", List.of(new SemanticAnalysisEvidence.ProjectProof(".", true, List.of("src/main/java"))),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of()));
        FullIndexPlan plan = new FullIndexPlanner().plan(root, List.of(root.resolve("src/main/java")));
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(root);
        MongoIndexBatchWriter batches = new MongoIndexBatchWriter(generations, context, new SourceIndexBatchDocumentMapper(template.getConverter()));
        for (FullIndexPlan.SourceInput source : plan.sources()) {
            List<SymbolDocument> symbols = new SyntaxSymbolProjector().project(repository, revision, target.generationId(),
                    syntax, source.sourcePath(), source.contentArtifact());
            batches.write(new SourceIndexBatch(repository, target.generationId(), source.sourcePath(), 0,
                    source.contentArtifact(), Optional.empty(), symbols, List.of(), List.of(),
                    new SearchProjector().project(symbols, List.of(), List.of())));
        }
        RepositoryProperties properties = new RepositoryProperties();
        SourceSnapshotPublication.PublishedSource source = new SourceSnapshotPublication(
                new JGitRepositoryAdapter(properties, JdtLsTestProperties.linuxUid()), new GitEvidencePublicationStore(template),
                properties.getGitEvidenceFileTextBytes()).publish(job, root, revision, plan, Optional.empty());
        MongoGenerationWriter.SourceOverview overview = generations.sourceOverview(context, source.policy().includedRoots(),
                source.excludedOrUnsupported(), 0);
        generations.recordSourceMembership(context, source.snapshot(), source.guide(), source.policy(), overview.coverage(), overview.structure());
        GenerationValidator validator = new GenerationValidator(template);
        GenerationValidator.ValidationResult validation = validator.validate(context, revision, revision, plan);
        assertThat(validation.valid()).as("published source validation: %s", validation.issues()).isTrue();
        validator.recordValid(context, validation);
        generations.seal(context, validation.identityDigest().value());
        new MongoPublicationWriter(template).publish(new PublishGenerationCommand(repository, revision, target.generationId(),
                job.id().value(), Optional.empty(), validation.identityDigest()));
        assertThat(jobs.complete(job.id())).isTrue();
    }

    private JsonNode currentContext(Map<String, Object> request) throws Exception {
        return sameHttpAndMcp("get_context", "/api/v1/context", request);
    }

    private JsonNode sameHttpAndMcp(String tool, String route, Map<String, Object> request) throws Exception {
        HttpResponse<String> response = http("POST", route, request);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = mapper.readTree(response.body());
        assertThat(applicationJson(tool, request, false)).isEqualTo(result);
        return result;
    }

    private void assertUnindexed(JsonNode result) {
        assertThat(result.get("state").asString()).isEqualTo("UNINDEXED");
        assertThat(result.has("revision")).isFalse();
        assertThat(result.has("context")).isFalse();
        assertThat(result.has("indexedAt")).isFalse();
    }

    private JsonNode applicationJson(String tool, Map<String, Object> arguments, boolean error) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        assertThat(result.isError()).isEqualTo(error);
        JsonNode structured = mapper.valueToTree(result.structuredContent());
        assertThat(result.content()).hasSize(1);
        McpSchema.TextContent text = (McpSchema.TextContent) result.content().getFirst();
        assertThat(mapper.readTree(text.text())).isEqualTo(structured);
        return structured;
    }

    private HttpResponse<String> http(String method, String route, Map<String, Object> body) throws Exception {
        HttpRequest.BodyPublisher publisher = method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(20))
                .header("X-Api-Token", "query-token").header("Content-Type", "application/json")
                .method(method, publisher).build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void publishRegistry(Set<String> repositories) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(directory.toString());
        for (String repository : repositories) {
            RepositoryProperties.RepositoryConfig configuration = new RepositoryProperties.RepositoryConfig();
            configuration.setUrl("https://never-contacted.invalid/" + repository + ".git");
            configuration.setDefaultBranch("main");
            properties.getRepositories().put(repository, configuration);
        }
        new ConfiguredRepositoryPublisher(template, new RepositoryRuntimeRegistry(properties)).publish();
    }
}
