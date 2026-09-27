package com.java.semantic.indexer.job;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.support.JdtLsTestProperties;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the production Git-review path with separately packaged Indexer and Query applications. */
@Tag("mongo-it")
class GitReviewContextJourneyIT {
    private static final String REPOSITORY_ID = "review-fixture";
    private static final String ADMIN_TOKEN = "journey-admin-token";
    private static final String QUERY_TOKEN = "journey-query-token";
    private static final String TOKEN_HEADER = "X-Api-Token";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration JOB_TIMEOUT = Duration.ofSeconds(45);
    private static final List<String> TOOL_NAMES = List.of(
            "list_repositories", "get_repository", "search_code", "get_fact_source", "list_entry_points", "find_api_routes",
            "find_event_listeners", "list_type_members", "find_method_implementations", "find_references", "find_callers", "find_callees",
            "list_git_branches", "list_git_commits", "compare_revisions", "get_file_diff", "list_files", "read_file", "search_text",
            "get_review", "review_search_code", "review_get_fact_source", "review_list_entry_points", "review_find_api_routes",
            "review_find_event_listeners", "review_list_type_members", "review_find_method_implementations", "review_find_references",
            "review_find_callers", "review_find_callees");

    @TempDir
    Path temporaryDirectory;

    @Test
    void follows_the_real_admin_dispatcher_mongo_http_and_mcp_git_review_journey_without_jdt() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("git.review.journey.enabled"),
                "the external process journey is opt-in and requires fresh executable jars");
        Path indexerJar = requiredJar("git.review.journey.indexer.jar");
        Path queryJar = requiredJar("git.review.journey.query.jar");
        Path remotePath = temporaryDirectory.resolve("review-remote.git");
        Path seedPath = temporaryDirectory.resolve("review-seed");
        Path missingJdtHome = temporaryDirectory.resolve("missing-jdt-home");
        Path jdtWorkspace = temporaryDirectory.resolve("jdt-workspace");
        int indexerPort = availablePort();
        int queryPort = availablePort();
        try (MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.4");
             Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            mongo.start();
            String mongoUri = mongo.getConnectionString() + "/git_review_journey";
            bootstrapSchema(indexerJar, mongoUri, missingJdtHome);
            String previous = seedFixture(seed, seedPath, remotePath);
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            String current = updateFixture(seed, seedPath);
            System.out.println("GRC4 fixture revisions previous=" + previous + " current=" + current + " review=" + previous);
            RunningProcess indexer = startIndexer(indexerJar, mongoUri, remotePath, indexerPort, missingJdtHome, jdtWorkspace);
            try {
                String indexerBase = "http://127.0.0.1:" + indexerPort;
                awaitHttp(indexerBase + "/index/repositories/" + REPOSITORY_ID + "/git/refs", QUERY_TOKEN, indexer);
                assertThat(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/git/refs", QUERY_TOKEN, Map.of()).statusCode())
                        .isEqualTo(401);

                JsonMapper mapper = JsonMapper.builder().build();
                String catalogJob = acceptedJob(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/git/refs", ADMIN_TOKEN, Map.of()), mapper);
                Map<?, ?> catalogStatus = completedJob(indexerBase, catalogJob, mapper, indexer);
                String catalogId = text(map(catalogStatus, "gitEvidence"), "evidenceId");

                String historyJob = acceptedJob(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/git/history", ADMIN_TOKEN,
                        Map.of("catalogId", catalogId, "branch", "main", "revision", current)), mapper);
                Map<?, ?> historyStatus = completedJob(indexerBase, historyJob, mapper, indexer);
                String historyId = text(map(historyStatus, "gitEvidence"), "evidenceId");

                String comparisonJob = acceptedJob(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/git/comparisons", ADMIN_TOKEN,
                        Map.of("previous", previous, "current", current)), mapper);
                Map<?, ?> comparisonStatus = completedJob(indexerBase, comparisonJob, mapper, indexer);
                Map<?, ?> comparisonEvidence = map(comparisonStatus, "gitEvidence");
                String comparisonId = text(comparisonEvidence, "comparisonId");
                String previousSnapshotId = text(comparisonEvidence, "previousSnapshotId");
                String currentSnapshotId = text(comparisonEvidence, "currentSnapshotId");
                assertThat(text(comparisonEvidence, "revision")).isEqualTo(current);

                seed.commit().setMessage("remote moved after evidence preparation").setAuthor("Fixture", "fixture@example.test")
                        .setCommitter("Fixture", "fixture@example.test").call();
                push(seed, "main");

                indexer.close();
                assertThat(Files.notExists(missingJdtHome)).isTrue();
                assertThat(Files.notExists(jdtWorkspace)).isTrue();
                Files.move(remotePath, temporaryDirectory.resolve("remote-unavailable"));
                Files.move(seedPath, temporaryDirectory.resolve("seed-unavailable"));
                Files.move(temporaryDirectory.resolve("checkouts"), temporaryDirectory.resolve("checkouts-unavailable"));

                assertDefaultPolicyDenies(queryJar, mongoUri, queryPort, mapper);
                RunningProcess query = startQuery(queryJar, mongoUri, queryPort, true, false);
                try {
                    String queryBase = "http://127.0.0.1:" + queryPort;
                    awaitHttp(queryBase + "/api/v1/git/branches", QUERY_TOKEN, query);
                    Map<?, ?> branches = successful(post(queryBase, "/api/v1/git/branches", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "catalogId", catalogId, "offset", 0, "limit", 1)), mapper);
                    assertThat(text(branches, "catalogId")).isEqualTo(catalogId);
                    assertThat(map(branches, "page").get("hasMore")).isEqualTo(Boolean.TRUE);
                    List<Map<?, ?>> branchItems = exhaustBranches(queryBase, catalogId, mapper);
                    assertThat(branchItems).extracting(item -> text(item, "head")).contains(current, previous);
                    Map<?, ?> commits = successful(post(queryBase, "/api/v1/git/commits", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "historyId", historyId, "revision", current, "offset", 0, "limit", 1)), mapper);
                    assertThat(text(commits, "historyId")).isEqualTo(historyId);
                    List<Map<?, ?>> history = exhaustHistory(queryBase, historyId, current, mapper);
                    assertThat(history).extracting(item -> text(item, "revision")).contains(current, previous);
                    Map<?, ?> comparison = successful(post(queryBase, "/api/v1/git/comparisons", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId, "previous", previous, "current", current,
                                    "offset", 0, "limit", 100)), mapper);
                    assertThat(text(comparison, "previousSnapshotId")).isEqualTo(previousSnapshotId);
                    assertThat(text(comparison, "currentSnapshotId")).isEqualTo(currentSnapshotId);
                    Map<?, ?> deleted = matching(mapList(comparison, "items"), "kind", "DELETE");
                    assertThat(text(deleted, "oldPath")).isEqualTo("src/DeletedPrevious.java");
                    String largeDiff = exhaustDiff(queryBase, comparisonId, previous, current,
                            text(matchingPath(mapList(comparison, "items"), "src/LargeDiff.java"), "changeId"), mapper);
                    assertThat(largeDiff).contains("changed-diff previous 0", "changed-diff current 6999");
                    Map<?, ?> files = successful(post(queryBase, "/api/v1/git/files", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "snapshotId", currentSnapshotId, "revision", current, "directory", "", "offset", 0,
                                    "limit", 1)), mapper);
                    assertThat(map(files, "coverage").get("readableTextCount")).isNotNull();
                    assertThat(map(files, "coverage").get("binaryCount")).isEqualTo(1);
                    assertThat(map(files, "coverage").get("tooLargeCount")).isEqualTo(1);
                    assertThat(exhaustFile(queryBase, currentSnapshotId, current, mapper)).isEqualTo(serviceSource("current"));
                    List<Map<?, ?>> stableMatches = exhaustSearch(queryBase, currentSnapshotId, current, "stable-token", mapper).items();
                    assertThat(stableMatches).hasSize(30);
                    assertThat(stableMatches).allSatisfy(match -> assertThat(text(match, "snippet")).contains("stable-token current"));
                    SearchTraversal noHitSearch = exhaustSearch(queryBase, currentSnapshotId, current, "definitely-absent", mapper);
                    assertThat(noHitSearch.items()).isEmpty();
                    assertThat(noHitSearch.pages()).isGreaterThan(1);
                    assertUnchangedAndDeletedFiles(queryBase, previousSnapshotId, currentSnapshotId, previous, current, mapper);
                    assertThat(post(queryBase, "/api/v1/git/branches", "wrong-token", Map.of("repositoryId", REPOSITORY_ID)).statusCode()).isEqualTo(401);
                    assertPendingEvidence(queryBase, mongoUri, mapper);
                    assertMcpJourney(queryBase, catalogId, historyId, comparisonId, previousSnapshotId, currentSnapshotId, previous, current, mapper);
                    assertPinnedCatalogAndHistory(queryBase, catalogId, historyId, current, branchItems, history, mapper);
                } finally {
                    query.close();
                }
                assertLaterPolicyDenies(queryJar, mongoUri, queryPort, mapper);
            } finally {
                indexer.close();
            }
        }
    }

    private static Path requiredJar(String property) {
        String value = System.getProperty(property, "");
        Path jar = Path.of(value).toAbsolutePath();
        assertThat(Files.isRegularFile(jar)).as("fresh executable jar supplied by the opt-in script: %s", property).isTrue();
        return jar;
    }

    private void bootstrapSchema(Path indexerJar, String mongoUri, Path missingJdtHome) throws Exception {
        RunningProcess bootstrap = start(indexerJar, List.of("--semantic.schema-bootstrap=true", "--spring.mongodb.uri=" + mongoUri,
                "--semantic.jdtls.home=" + missingJdtHome, "--spring.main.banner-mode=off"));
        try {
            assertThat(bootstrap.await(Duration.ofSeconds(30))).isZero();
        } finally {
            bootstrap.close();
        }
    }

    private String seedFixture(Git seed, Path seedPath, Path remotePath) throws Exception {
        write(seedPath, "src/Caller.java", "class Caller { int call() { return Service.version(); } }\n");
        write(seedPath, "src/Service.java", serviceSource("previous"));
        write(seedPath, "src/LargeDiff.java", largeDiffSource("previous"));
        write(seedPath, "src/HugeNoHit.java", hugeNoHitSource());
        writeNoHitFiles(seedPath);
        write(seedPath, "src/DeletedPrevious.java", "class DeletedPrevious { }\n");
        write(seedPath, "src/ServiceTest.java", "class ServiceTest { }\n");
        write(seedPath, "config/review.properties", "review.mode=synthetic\n");
        write(seedPath, "docs/review.md", "# Synthetic review fixture\n");
        writeBinary(seedPath, "assets/binary.dat");
        seed.add().addFilepattern(".").call();
        String revision = seed.commit().setMessage("initial review source").setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").call().getId().name();
        seed.remoteAdd().setName("origin").setUri(new URIish(remotePath.toUri().toString())).call();
        push(seed, "main");
        seed.branchCreate().setName("review").setStartPoint(revision).call();
        push(seed, "review");
        return revision;
    }

    private String updateFixture(Git seed, Path seedPath) throws Exception {
        write(seedPath, "src/Service.java", serviceSource("current"));
        write(seedPath, "src/LargeDiff.java", largeDiffSource("current"));
        Files.delete(seedPath.resolve("src/DeletedPrevious.java"));
        seed.add().addFilepattern("src/Service.java").call();
        seed.add().addFilepattern("src/LargeDiff.java").call();
        seed.rm().addFilepattern("src/DeletedPrevious.java").call();
        String revision = seed.commit().setMessage("change service and delete prior evidence").setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").call().getId().name();
        push(seed, "main");
        return revision;
    }

    private static String serviceSource(String value) {
        StringBuilder source = new StringBuilder("class Service {\n  static int version() { return 2; }\n");
        for (int index = 0; index < 30; index++) {
            source.append("  // stable-token ").append(value).append(' ').append(index).append("\n");
        }
        return source.append("}\n").toString();
    }

    private static String largeDiffSource(String value) {
        StringBuilder source = new StringBuilder("class LargeDiff {\n");
        for (int index = 0; index < 7000; index++) {
            source.append("  // changed-diff ").append(value).append(' ').append(index).append("\n");
        }
        return source.append("}\n").toString();
    }

    private static String hugeNoHitSource() {
        StringBuilder source = new StringBuilder("class HugeNoHit {\n");
        String padding = "x".repeat(1_000);
        for (int index = 0; index < 4_500; index++) {
            source.append("  // searchable-text ").append(index).append(' ').append(padding).append("\n");
        }
        return source.append("}\n").toString();
    }

    private static void writeNoHitFiles(Path root) throws IOException {
        for (int index = 0; index < 72; index++) {
            write(root, "src/nohit/NoHit" + index + ".java", noHitSource(index));
        }
    }

    private static String noHitSource(int index) {
        StringBuilder source = new StringBuilder("class NoHit" + index + " {\n");
        String padding = "x".repeat(1_000);
        for (int line = 0; line < 64; line++) {
            source.append("  // searchable-text ").append(index).append(' ').append(line).append(' ').append(padding).append("\n");
        }
        return source.append("}\n").toString();
    }

    private RunningProcess startIndexer(Path jar, String mongoUri, Path remotePath, int port, Path missingJdtHome, Path jdtWorkspace)
            throws IOException {
        Path checkoutRoot = Files.createDirectories(temporaryDirectory.resolve("checkouts"));
        JdtLsProperties isolation = JdtLsTestProperties.linuxUid();
        return start(jar, List.of("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1", "--server.port=" + port,
                "--semantic.indexer.admin-token=" + ADMIN_TOKEN, "--semantic.repositories." + REPOSITORY_ID + ".url=" + remotePath.toUri(),
                "--semantic.repositories." + REPOSITORY_ID + ".default-branch=main", "--semantic.data-root=" + checkoutRoot,
                "--semantic.jdtls.home=" + missingJdtHome, "--semantic.jdtls.workspace-data-root=" + jdtWorkspace,
                "--semantic.jdtls.isolation-mode=" + isolation.getIsolationMode(),
                "--semantic.jdtls.analysis-uid=" + isolation.getAnalysisUid(),
                "--semantic.jdtls.analysis-gid=" + isolation.getAnalysisGid(),
                "--semantic.index-jobs.poll-delay=20ms", "--spring.main.banner-mode=off"));
    }

    private RunningProcess startQuery(Path jar, String mongoUri, int port, boolean allowed, boolean forbidden) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1", "--server.port=" + port,
                "--semantic.query.api-token=" + QUERY_TOKEN, "--spring.main.banner-mode=off"));
        if (allowed) {
            arguments.add("--semantic.query.git-evidence.allowed-repositories[0]=" + REPOSITORY_ID);
        }
        if (forbidden) {
            arguments.add("--semantic.query.read-policy.forbidden-repositories[0]=" + REPOSITORY_ID);
        }
        return start(jar, List.copyOf(arguments));
    }

    private RunningProcess start(Path jar, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(arguments);
        Path log = Files.createTempFile(temporaryDirectory, "journey-process-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new RunningProcess(process, log);
    }

    private void assertDefaultPolicyDenies(Path queryJar, String mongoUri, int port, JsonMapper mapper) throws Exception {
        RunningProcess query = startQuery(queryJar, mongoUri, port, false, false);
        try {
            String base = "http://127.0.0.1:" + port;
            awaitHttp(base + "/api/v1/git/branches", QUERY_TOKEN, query);
            HttpResponse<String> response = post(base, "/api/v1/git/branches", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID));
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(mapper.readTree(response.body()).get("code").asString()).isEqualTo("REPOSITORY_NOT_FOUND");
        } finally {
            query.close();
        }
    }

    private void assertLaterPolicyDenies(Path queryJar, String mongoUri, int port, JsonMapper mapper) throws Exception {
        RunningProcess query = startQuery(queryJar, mongoUri, port, true, true);
        try {
            String base = "http://127.0.0.1:" + port;
            awaitHttp(base + "/api/v1/git/branches", QUERY_TOKEN, query);
            HttpResponse<String> response = post(base, "/api/v1/git/branches", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID));
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(mapper.readTree(response.body()).get("code").asString()).isEqualTo("REPOSITORY_NOT_FOUND");
        } finally {
            query.close();
        }
    }

    private void assertPendingEvidence(String queryBase, String mongoUri, JsonMapper mapper) throws Exception {
        String pendingId = "11111111-1111-1111-1111-111111111111";
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoDatabase database = client.getDatabase("git_review_journey");
            database.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).insertOne(new org.bson.Document("repoId", REPOSITORY_ID)
                    .append("evidenceId", pendingId).append("kind", "CATALOG").append("state", "PREPARING").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE"));
        }
        HttpResponse<String> response = post(queryBase, "/api/v1/git/branches", QUERY_TOKEN,
                Map.of("repositoryId", REPOSITORY_ID, "catalogId", pendingId, "offset", 0, "limit", 1));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(response.body()).get("code").asString()).isEqualTo("GIT_EVIDENCE_NOT_READY");
    }

    private void assertMcpJourney(String queryBase, String catalogId, String historyId, String comparisonId, String previousSnapshotId,
                                  String currentSnapshotId, String previous, String current, JsonMapper mapper) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(queryBase + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header(TOKEN_HEADER, QUERY_TOKEN)).build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(30)).build()) {
            assertThat(client.initialize().serverInfo()).isNotNull();
            McpSchema.ListToolsResult tools = client.listTools();
            assertThat(tools.tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
            Map<?, ?> branches = mcpBody(client, "list_git_branches", Map.of("repositoryId", REPOSITORY_ID, "catalogId", catalogId), mapper);
            assertThat(text(branches, "catalogId")).isEqualTo(catalogId);
            Map<?, ?> commits = mcpBody(client, "list_git_commits", Map.of("repositoryId", REPOSITORY_ID, "historyId", historyId, "revision", current), mapper);
            assertThat(text(commits, "historyId")).isEqualTo(historyId);
            Map<?, ?> comparison = mcpBody(client, "compare_revisions", Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId,
                    "previous", previous, "current", current), mapper);
            Map<?, ?> modified = matchingPath(mapList(comparison, "items"), "src/LargeDiff.java");
            mcpBody(client, "get_file_diff", Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId, "previous", previous,
                    "current", current, "changeId", text(modified, "changeId")), mapper);
            Map<?, ?> files = mcpBody(client, "list_files", Map.of("repositoryId", REPOSITORY_ID, "snapshotId", currentSnapshotId,
                    "revision", current, "directory", ""), mapper);
            assertThat(map(files, "coverage").get("inventoryCount")).isNotNull();
            Map<?, ?> file = mcpBody(client, "read_file", Map.of("repositoryId", REPOSITORY_ID, "snapshotId", previousSnapshotId,
                    "revision", previous, "path", "src/Service.java", "startLine", 3, "maxLines", 1), mapper);
            assertThat(text(file, "content")).contains("stable-token");
            Map<?, ?> search = mcpBody(client, "search_text", Map.of("repositoryId", REPOSITORY_ID, "snapshotId", currentSnapshotId,
                    "revision", current, "query", "stable-token", "limit", 1), mapper);
            assertThat(mapList(search, "items")).isNotEmpty();
            Map<?, ?> deleted = matching(mapList(comparison, "items"), "kind", "DELETE");
            Map<?, ?> terminalDiff = mcpBody(client, "get_file_diff", Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId,
                    "previous", previous, "current", current, "changeId", text(deleted, "changeId")), mapper);
            assertNoCursor(terminalDiff);
            Map<?, ?> terminalFile = mcpBody(client, "read_file", Map.of("repositoryId", REPOSITORY_ID, "snapshotId", previousSnapshotId,
                    "revision", previous, "path", "src/DeletedPrevious.java"), mapper);
            assertNoCursor(terminalFile);
            McpSearchTraversal terminalSearch = exhaustMcpSearch(client, currentSnapshotId, current, "stable-token", mapper);
            assertThat(terminalSearch.items()).hasSize(30);
            assertThat(terminalSearch.items()).allSatisfy(match -> assertThat(text(match, "snippet")).contains("stable-token current"));
            assertThat(terminalSearch.finalPage().get("scanComplete")).isEqualTo(Boolean.TRUE);
            assertNoCursor(terminalSearch.finalPage());
        }
    }

    private static Map<?, ?> mcpBody(McpSyncClient client, String toolName, Map<String, Object> arguments, JsonMapper mapper) {
        McpSchema.CallToolResult response = client.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
        assertThat(response.isError()).as("%s must succeed: structured=%s content=%s", toolName, response.structuredContent(), response.content()).isFalse();
        return mapper.convertValue(response.structuredContent(), Map.class);
    }

    private static McpSearchTraversal exhaustMcpSearch(McpSyncClient client, String snapshotId, String revision, String query,
                                                        JsonMapper mapper) {
        Map<String, Object> arguments = new java.util.HashMap<>();
        arguments.put("repositoryId", REPOSITORY_ID);
        arguments.put("snapshotId", snapshotId);
        arguments.put("revision", revision);
        arguments.put("query", query);
        arguments.put("limit", 1);
        List<Map<?, ?>> items = new ArrayList<>();
        while (true) {
            Map<?, ?> page = mcpBody(client, "search_text", arguments, mapper);
            items.addAll(mapList(page, "items"));
            if (Boolean.TRUE.equals(page.get("scanComplete"))) {
                return new McpSearchTraversal(List.copyOf(items), page);
            }
            arguments.put("cursor", text(page, "nextCursor"));
        }
    }

    private static void assertNoCursor(Map<?, ?> body) {
        assertThat(body.containsKey("nextCursor")).isFalse();
    }

    private String exhaustDiff(String base, String comparisonId, String previous, String current, String changeId, JsonMapper mapper) throws Exception {
        Map<String, Object> request = Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId, "previous", previous, "current", current,
                "changeId", changeId);
        Map<?, ?> page = successful(post(base, "/api/v1/git/file-diff", QUERY_TOKEN, request), mapper);
        StringBuilder patch = new StringBuilder();
        while (true) {
            assertThat(text(map(page, "change"), "changeId")).isEqualTo(changeId);
            patch.append(text(page, "patch"));
            if (!page.containsKey("nextCursor")) {
                return patch.toString();
            }
            page = successful(post(base, "/api/v1/git/file-diff", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId,
                    "previous", previous, "current", current, "changeId", changeId, "cursor", text(page, "nextCursor"))), mapper);
        }
    }

    private String exhaustFile(String base, String snapshotId, String revision, JsonMapper mapper) throws Exception {
        Map<?, ?> page = successful(post(base, "/api/v1/git/file", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID, "snapshotId", snapshotId,
                "revision", revision, "path", "src/Service.java", "maxLines", 1)), mapper);
        StringBuilder content = new StringBuilder();
        while (true) {
            content.append(text(page, "content"));
            if (!page.containsKey("nextCursor")) {
                return content.toString();
            }
            page = successful(post(base, "/api/v1/git/file", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID, "snapshotId", snapshotId,
                    "revision", revision, "path", "src/Service.java", "maxLines", 1, "cursor", text(page, "nextCursor"))), mapper);
        }
    }

    private SearchTraversal exhaustSearch(String base, String snapshotId, String revision, String query, JsonMapper mapper) throws Exception {
        Map<?, ?> page = successful(post(base, "/api/v1/git/search", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID, "snapshotId", snapshotId,
                "revision", revision, "query", query, "limit", 1)), mapper);
        List<Map<?, ?>> items = new ArrayList<>();
        int pages = 0;
        while (true) {
            items.addAll(mapList(page, "items"));
            pages++;
            if (Boolean.TRUE.equals(page.get("scanComplete"))) {
                return new SearchTraversal(List.copyOf(items), pages);
            }
            String cursor = text(page, "nextCursor");
            HttpResponse<String> continuation = post(base, "/api/v1/git/search", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID,
                    "snapshotId", snapshotId, "revision", revision, "query", query, "limit", 1, "cursor", cursor));
            assertThat(continuation.statusCode()).as("query=%s cursor=%s body=%s", query, cursor, continuation.body()).isEqualTo(200);
            page = mapper.readValue(continuation.body(), Map.class);
        }
    }

    private List<Map<?, ?>> exhaustBranches(String base, String catalogId, JsonMapper mapper) throws Exception {
        return exhaustOffsetPages(base, "/api/v1/git/branches", Map.of("repositoryId", REPOSITORY_ID, "catalogId", catalogId), "catalogId", catalogId, mapper);
    }

    private List<Map<?, ?>> exhaustHistory(String base, String historyId, String revision, JsonMapper mapper) throws Exception {
        return exhaustOffsetPages(base, "/api/v1/git/commits", Map.of("repositoryId", REPOSITORY_ID, "historyId", historyId, "revision", revision),
                "historyId", historyId, mapper);
    }

    private List<Map<?, ?>> exhaustOffsetPages(String base, String path, Map<String, String> identity, String identityKey, String identityValue,
                                                 JsonMapper mapper) throws Exception {
        List<Map<?, ?>> items = new ArrayList<>();
        int offset = 0;
        while (true) {
            Map<String, Object> request = new java.util.HashMap<>(identity);
            request.put("offset", offset);
            request.put("limit", 1);
            Map<?, ?> page = successful(post(base, path, QUERY_TOKEN, request), mapper);
            assertThat(text(page, identityKey)).isEqualTo(identityValue);
            List<Map<?, ?>> pageItems = mapList(page, "items");
            items.addAll(pageItems);
            if (!Boolean.TRUE.equals(map(page, "page").get("hasMore"))) {
                return List.copyOf(items);
            }
            offset += pageItems.size();
        }
    }

    private void assertPinnedCatalogAndHistory(String base, String catalogId, String historyId, String revision, List<Map<?, ?>> branches,
                                                List<Map<?, ?>> history, JsonMapper mapper) throws Exception {
        assertThat(exhaustBranches(base, catalogId, mapper)).isEqualTo(branches);
        assertThat(exhaustHistory(base, historyId, revision, mapper)).isEqualTo(history);
    }

    private void assertUnchangedAndDeletedFiles(String base, String previousSnapshotId, String currentSnapshotId, String previous, String current,
                                                JsonMapper mapper) throws Exception {
        assertThat(readFile(base, currentSnapshotId, current, "src/Caller.java", mapper)).isEqualTo("class Caller { int call() { return Service.version(); } }\n");
        assertThat(readFile(base, currentSnapshotId, current, "src/ServiceTest.java", mapper)).isEqualTo("class ServiceTest { }\n");
        assertThat(readFile(base, currentSnapshotId, current, "config/review.properties", mapper)).isEqualTo("review.mode=synthetic\n");
        assertThat(readFile(base, currentSnapshotId, current, "docs/review.md", mapper)).isEqualTo("# Synthetic review fixture\n");
        assertThat(readFile(base, previousSnapshotId, previous, "src/DeletedPrevious.java", mapper)).isEqualTo("class DeletedPrevious { }\n");
    }

    private String readFile(String base, String snapshotId, String revision, String path, JsonMapper mapper) throws Exception {
        Map<?, ?> page = successful(post(base, "/api/v1/git/file", QUERY_TOKEN, Map.of("repositoryId", REPOSITORY_ID, "snapshotId", snapshotId,
                "revision", revision, "path", path)), mapper);
        assertThat(page.containsKey("nextCursor")).isFalse();
        return text(page, "content");
    }

    private Map<?, ?> completedJob(String base, String jobId, JsonMapper mapper, RunningProcess indexer) throws Exception {
        Instant deadline = Instant.now().plus(JOB_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get(base, "/index/repositories/" + REPOSITORY_ID + "/jobs/" + jobId, ADMIN_TOKEN);
            if (response.statusCode() == 200) {
                Map<?, ?> status = mapper.readValue(response.body(), Map.class);
                if ("COMPLETE".equals(status.get("phase"))) {
                    return status;
                }
                if ("FAILED".equals(status.get("phase"))) {
                    throw new AssertionError("job " + jobId + " failed: " + status.get("failureCategory")
                            + "\n" + Files.readString(indexer.log()));
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("job did not complete: " + jobId);
    }

    private static String acceptedJob(HttpResponse<String> response, JsonMapper mapper) throws Exception {
        assertThat(response.statusCode()).isEqualTo(202);
        return text(mapper.readValue(response.body(), Map.class), "jobId");
    }

    private static Map<?, ?> successful(HttpResponse<String> response, JsonMapper mapper) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readValue(response.body(), Map.class);
    }

    private void awaitHttp(String url, String token, RunningProcess process) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.process().isAlive()) {
                throw new AssertionError("application terminated during startup: " + Files.readString(process.log()));
            }
            try {
                HttpResponse<String> response = postUri(url, token, "{}");
                if (response.statusCode() < 500) {
                    return;
                }
            } catch (IOException exception) {
                // The process has not opened its local socket yet.
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("application did not open its local HTTP endpoint: " + Files.readString(process.log()));
    }

    private static HttpResponse<String> post(String base, String path, String token, Map<String, Object> body) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        return postUri(base + path, token, mapper.writeValueAsString(body));
    }

    private static HttpResponse<String> postUri(String uri, String token, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).header(TOKEN_HEADER, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String base, String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).header(TOKEN_HEADER, token).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static void writeBinary(Path root, String relativePath) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {0, 1, 2});
    }

    private static void push(Git seed, String branch) throws Exception {
        seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch)).call();
    }

    private static Map<?, ?> matching(List<Map<?, ?>> items, String field, String value) {
        return items.stream().filter(item -> value.equals(item.get(field))).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + field + "=" + value));
    }

    private static Map<?, ?> matchingPath(List<Map<?, ?>> items, String path) {
        return items.stream().filter(item -> path.equals(item.get("newPath"))).findFirst()
                .orElseThrow(() -> new AssertionError("missing changed path " + path));
    }

    private static List<Map<?, ?>> mapList(Map<?, ?> body, String field) {
        Object value = body.get(field);
        assertThat(value).isInstanceOf(List.class);
        List<Map<?, ?>> items = new ArrayList<>();
        for (Object item : (List<?>) value) {
            assertThat(item).isInstanceOf(Map.class);
            items.add((Map<?, ?>) item);
        }
        return List.copyOf(items);
    }

    private static Map<?, ?> map(Map<?, ?> body, String field) {
        Object value = body.get(field);
        assertThat(value).isInstanceOf(Map.class);
        return (Map<?, ?>) value;
    }

    private static String text(Map<?, ?> body, String field) {
        Object value = body.get(field);
        assertThat(value).isInstanceOf(String.class);
        return (String) value;
    }

    private record SearchTraversal(List<Map<?, ?>> items, int pages) {
    }

    private record McpSearchTraversal(List<Map<?, ?>> items, Map<?, ?> finalPage) {
    }

    private record RunningProcess(Process process, Path log) implements AutoCloseable {
        private int await(Duration timeout) throws InterruptedException {
            boolean completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            assertThat(completed).isTrue();
            return process.exitValue();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
