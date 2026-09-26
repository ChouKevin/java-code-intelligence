package com.java.semantic.indexer.job;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
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
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the separately packaged services against JDT-produced review evidence, then reads it cold. */
@Tag("jdtls-it")
class SemanticReviewJourneyIT {
    private static final String REPOSITORY_ID = "semantic-review-journey";
    private static final String ADMIN_TOKEN = "semantic-review-journey-admin";
    private static final String QUERY_TOKEN = "semantic-review-journey-reader";
    private static final String DATABASE = "semantic_review_journey";
    private static final String INDEXER_IMAGE_PROPERTY = "semantic.review.journey.indexer.image";
    private static final String MONGO_NETWORK_ALIAS = "semantic-review-mongo";
    private static final String REMOTE_CONTAINER_PATH = "/tmp/semantic-review-journey-remote.git";
    private static final int INDEXER_CONTAINER_PORT = 8080;
    private static final int MONGO_CONTAINER_PORT = 27017;
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(3);
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
    void publishes_real_jdt_review_evidence_then_serves_http_and_mcp_from_read_only_cold_mongo() throws Exception {
        assertThat(Boolean.getBoolean("semantic.review.journey.enabled"))
                .as("the external semantic journey must be explicitly enabled").isTrue();
        Path indexerJar = requiredJar("semantic.review.journey.indexer.jar");
        Path queryJar = requiredJar("semantic.review.journey.query.jar");
        Path remotePath = temporaryDirectory.resolve("semantic-review-remote.git");
        Path seedPath = temporaryDirectory.resolve("semantic-review-seed");
        int queryPort = availablePort();
        String indexerImage = requiredImage(INDEXER_IMAGE_PROPERTY);
        String reviewId;
        String indexerLogs;

        try (Network network = Network.newNetwork();
             MongoDBContainer mongo = authenticatedMongo(network);
             Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            mongo.start();
            String writerUri = mongoUri(mongo, "root", "root-password", DATABASE, "admin");
            createReadOnlyUser(writerUri);
            String readerUri = mongoUri(mongo, "review-reader", "read-password", DATABASE, DATABASE);
            assertReadOnly(readerUri);
            String revisionA = commitA(seed, seedPath, remotePath);
            assertCleanMavenSeed(seedPath);
            String revisionB = commitB(seed, seedPath);
            bootstrapSchema(indexerJar, writerUri);
            remote.getRepository().updateRef(Constants.HEAD, true).link(Constants.R_HEADS + "main");
            String indexerMongoUri = mongoUri(MONGO_NETWORK_ALIAS, MONGO_CONTAINER_PORT, "root", "root-password", DATABASE, "admin");
            JsonMapper mapper = JsonMapper.builder().build();
            try (GenericContainer<?> indexer = startIndexer(indexerImage, indexerMongoUri, remotePath, network)) {
                try {
                    String indexerBase = "http://" + indexer.getHost() + ":" + indexer.getMappedPort(INDEXER_CONTAINER_PORT);
                    awaitHttp(indexerBase + "/index/repositories/" + REPOSITORY_ID + "/publication", ADMIN_TOKEN, indexer);
                    String checkoutJob = accepted(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/checkout", ADMIN_TOKEN,
                            Map.of("revision", revisionA)), mapper);
                    Map<?, ?> currentA = completed(indexerBase, checkoutJob, mapper, indexer);
                    Map<?, ?> currentPointerA = map(currentA, "currentPointer");
                    assertThat(text(currentPointerA, "revision")).isEqualTo(revisionA);
                    String baselineGeneration = text(currentPointerA, "generationId");
                    assertThat(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/reviews", QUERY_TOKEN,
                            Map.of("revision", revisionB)).statusCode()).isEqualTo(401);
                    Map<?, ?> acceptedReviewBody = acceptedReview(post(indexerBase,
                            "/index/repositories/" + REPOSITORY_ID + "/reviews", ADMIN_TOKEN, Map.of("revision", revisionB)), mapper);
                    String reviewJob = text(acceptedReviewBody, "jobId");
                    Map<?, ?> admittedReview = successful(get(indexerBase,
                            "/index/repositories/" + REPOSITORY_ID + "/jobs/" + reviewJob, ADMIN_TOKEN), mapper);
                    assertThat(text(admittedReview, "jobId")).isEqualTo(reviewJob);
                    assertThat(text(admittedReview, "operation")).isEqualTo("REVIEW");
                    assertReviewComparisonType(admittedReview);
                    Map<?, ?> admittedReviewDetails = map(admittedReview, "review");
                    Map<?, ?> admittedBaseline = map(admittedReviewDetails, "capturedBaseline");
                    assertThat(text(admittedBaseline, "generationId")).isEqualTo(baselineGeneration);
                    assertThat(text(admittedBaseline, "revision")).isEqualTo(revisionA);
                    assertThat(text(admittedReviewDetails, "requestedRevision")).isEqualTo(revisionB);
                    assertThat(map(admittedReview, "currentPointer")).isEqualTo(currentPointerA);

                    Map<?, ?> completeReview = completed(indexerBase, reviewJob, mapper, indexer);
                    assertReviewComparisonType(completeReview);
                    Map<?, ?> review = map(completeReview, "review");
                    reviewId = text(review, "reviewId");
                    Map<?, ?> completedBaseline = map(review, "capturedBaseline");
                    assertThat(text(completedBaseline, "generationId")).isEqualTo(baselineGeneration);
                    assertThat(text(completedBaseline, "revision")).isEqualTo(revisionA);
                    assertThat(text(review, "requestedRevision")).isEqualTo(revisionB);
                } catch (AssertionError failure) {
                    throw withIndexerLogs(failure, indexer.getLogs());
                } finally {
                    indexerLogs = indexer.getLogs();
                }
            }

            try {
                Files.move(remotePath, temporaryDirectory.resolve("remote-unavailable"));
                Files.move(seedPath, temporaryDirectory.resolve("seed-unavailable"));

                RunningProcess query = startQuery(queryJar, readerUri, queryPort);
                try {
                    String queryBase = "http://127.0.0.1:" + queryPort;
                    awaitHttp(queryBase + "/api/v1/repositories/" + REPOSITORY_ID + "/reviews/" + reviewId, QUERY_TOKEN, query);
                    Map<?, ?> reviewDetails = successful(get(queryBase, "/api/v1/repositories/" + REPOSITORY_ID + "/reviews/" + reviewId,
                            QUERY_TOKEN), mapper);
                    assertThat(text(map(reviewDetails, "a"), "revision")).isEqualTo(revisionA);
                    assertThat(text(map(reviewDetails, "b"), "revision")).isEqualTo(revisionB);
                    assertThat(text(map(reviewDetails, "a"), "snapshotId")).isNotEqualTo(text(map(reviewDetails, "b"), "snapshotId"));

                    Map<String, Object> searchA = reviewSearch(reviewId, "A", revisionA, "LegacyGateway", "TYPE");
                    Map<?, ?> legacySearch = successful(post(queryBase, "/api/v1/reviews/search-code", QUERY_TOKEN, searchA), mapper);
                    String legacyGateway = text(mapList(map(legacySearch, "result"), "items").getFirst(), "factId");
                    Map<?, ?> legacySource = successful(post(queryBase, "/api/v1/reviews/fact-source", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId, "side", "A", "revision", revisionA,
                                    "factId", legacyGateway)), mapper);
                    assertThat(text(map(map(legacySource, "result"), "source"), "code")).contains("LegacyGateway");
                    String legacyPay = methodFact(queryBase, reviewId, "A", revisionA, "LegacyGateway", mapper);
                    Map<?, ?> legacyCallers = successful(post(queryBase, "/api/v1/reviews/callers", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId, "side", "A", "revision", revisionA,
                                    "methodFactId", legacyPay)), mapper);
                    assertThat(mapList(map(legacyCallers, "result"), "items")).isNotEmpty();

                    Map<String, Object> searchB = reviewSearch(reviewId, "B", revisionB, "ModernGateway", "TYPE");
                    Map<?, ?> searchResult = successful(post(queryBase, "/api/v1/reviews/search-code", QUERY_TOKEN, searchB), mapper);
                    String modernGateway = text(mapList(map(searchResult, "result"), "items").getFirst(), "factId");
                    Map<?, ?> source = successful(post(queryBase, "/api/v1/reviews/fact-source", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId, "side", "B", "revision", revisionB,
                                    "factId", modernGateway)), mapper);
                    assertThat(text(map(map(source, "result"), "source"), "code")).contains("ModernGateway");
                    String modernPay = methodFact(queryBase, reviewId, "B", revisionB, "ModernGateway", mapper);
                    Map<?, ?> callers = successful(post(queryBase, "/api/v1/reviews/callers", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId, "side", "B", "revision", revisionB,
                                    "methodFactId", modernPay)), mapper);
                    assertThat(mapList(map(callers, "result"), "items")).isNotEmpty();

                    String comparisonId = text(reviewDetails, "comparisonId");
                    Map<?, ?> comparison = successful(post(queryBase, "/api/v1/git/comparisons", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId, "previous", revisionA, "current", revisionB)), mapper);
                    Map<?, ?> checkoutChange = mapList(comparison, "items").stream()
                            .filter(item -> "src/main/java/example/Checkout.java".equals(item.get("newPath"))).findFirst().orElseThrow();
                    Map<?, ?> patch = successful(post(queryBase, "/api/v1/git/file-diff", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId, "previous", revisionA, "current", revisionB,
                                    "changeId", text(checkoutChange, "changeId"))), mapper);
                    assertThat(text(patch, "patch")).contains("LegacyGateway", "ModernGateway");
                    assertMcpJourney(queryBase, reviewId, revisionA, revisionB, comparisonId, legacyGateway, legacyPay, modernGateway, modernPay,
                            mapper);
                } finally {
                    query.close();
                }
            } catch (AssertionError failure) {
                throw withIndexerLogs(failure, indexerLogs);
            }
        }
    }

    private static MongoDBContainer authenticatedMongo(Network network) {
        return new MongoDBContainer("mongo:8.0.4").withEnv("MONGO_INITDB_ROOT_USERNAME", "root")
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", "root-password").withNetwork(network).withNetworkAliases(MONGO_NETWORK_ALIAS);
    }

    private static String mongoUri(MongoDBContainer mongo, String user, String password, String database, String authSource) {
        return mongoUri(mongo.getHost(), mongo.getFirstMappedPort(), user, password, database, authSource);
    }

    private static String mongoUri(String host, int port, String user, String password, String database, String authSource) {
        return "mongodb://" + user + ":" + password + "@" + host + ":" + port + "/" + database + "?authSource=" + authSource;
    }

    private static Path requiredJar(String property) {
        Path jar = Path.of(System.getProperty(property, "")).toAbsolutePath();
        assertThat(Files.isRegularFile(jar)).as("fresh executable jar supplied by %s", property).isTrue();
        return jar;
    }

    private static String requiredImage(String property) {
        String image = System.getProperty(property, "");
        assertThat(image).as("local production Indexer image supplied by %s", property).isNotBlank();
        return image;
    }

    private static void assertCleanMavenSeed(Path root) {
        assertThat(Files.exists(root.resolve(".project"))).as("Maven seed A has no precreated .project").isFalse();
        assertThat(Files.exists(root.resolve(".classpath"))).as("Maven seed A has no precreated .classpath").isFalse();
        assertThat(Files.exists(root.resolve(".settings"))).as("Maven seed A has no precreated .settings").isFalse();
        assertThat(Files.exists(root.resolve("target"))).as("Maven seed A has no precreated target").isFalse();
    }

    private static void createReadOnlyUser(String writerUri) {
        try (MongoClient client = MongoClients.create(writerUri)) {
            MongoDatabase database = client.getDatabase(DATABASE);
            database.runCommand(new Document("createUser", "review-reader").append("pwd", "read-password")
                    .append("roles", List.of(new Document("role", "read").append("db", DATABASE))));
        }
    }

    private static void assertReadOnly(String readerUri) {
        try (MongoClient client = MongoClients.create(readerUri)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.getDatabase(DATABASE)
                    .getCollection("read_only_probe").insertOne(new Document("probe", true)))
                    .isInstanceOf(com.mongodb.MongoCommandException.class);
        }
    }

    private void bootstrapSchema(Path indexerJar, String mongoUri) throws Exception {
        try (RunningProcess bootstrap = start(indexerJar, List.of("--semantic.schema-bootstrap=true", "--spring.mongodb.uri=" + mongoUri,
                "--spring.main.banner-mode=off"))) {
            assertThat(bootstrap.await(Duration.ofSeconds(30))).isZero();
        }
    }

    private String commitA(Git seed, Path root, Path remote) throws Exception {

        write(root, "pom.xml", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>journey</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>\n");
        write(root, "src/main/resources/.gitkeep", "");
        write(root, "src/main/java/example/Gateway.java", "package example; public interface Gateway { void pay(); }\n");
        write(root, "src/main/java/example/LegacyGateway.java", "package example; public class LegacyGateway implements Gateway { public void pay() {} }\n");
        write(root, "src/main/java/example/Checkout.java", "package example; public class Checkout { public void place() { new LegacyGateway().pay(); } }\n");
        seed.add().addFilepattern(".").call();
        String revision = seed.commit().setMessage("legacy").setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").call().getId().name();
        seed.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toString())).call();
        push(seed);
        return revision;
    }

    private String commitB(Git seed, Path root) throws Exception {
        Files.delete(root.resolve("src/main/java/example/LegacyGateway.java"));
        write(root, "src/main/java/example/ModernGateway.java", "package example; public class ModernGateway implements Gateway { public void pay() {} }\n");
        write(root, "src/main/java/example/Checkout.java", "package example; public class Checkout { public void place() { new ModernGateway().pay(); } }\n");
        seed.add().addFilepattern(".").call();
        seed.add().setUpdate(true).addFilepattern(".").call();
        String revision = seed.commit().setMessage("modern").setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").call().getId().name();
        push(seed);
        return revision;
    }

    private GenericContainer<?> startIndexer(String image, String mongoUri, Path remote, Network network) {
        GenericContainer<?> indexer = new GenericContainer<>(DockerImageName.parse(image))
                .withNetwork(network)
                .withExposedPorts(INDEXER_CONTAINER_PORT)
                .withTmpFs(Map.of("/data/repos", "rw,noexec,nosuid,nodev,size=1g", "/data/jdtls", "rw,noexec,nosuid,nodev,size=1g"))
                .withFileSystemBind(remote.toAbsolutePath().toString(), REMOTE_CONTAINER_PATH, BindMode.READ_ONLY)
                .withCommand("--spring.mongodb.uri=" + mongoUri, "--server.address=0.0.0.0",
                        "--server.port=" + INDEXER_CONTAINER_PORT, "--semantic.indexer.admin-token=" + ADMIN_TOKEN,
                        "--semantic.repositories." + REPOSITORY_ID + ".url=file://" + REMOTE_CONTAINER_PATH,
                        "--semantic.repositories." + REPOSITORY_ID + ".default-branch=main",
                        "--semantic.index-jobs.poll-delay=20ms", "--spring.main.banner-mode=off");
        indexer.start();
        return indexer;
    }

    private RunningProcess startQuery(Path jar, String mongoUri, int port) throws IOException {
        List<String> command = command(jar, List.of("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1",
                "--server.port=" + port, "--semantic.query.api-token=" + QUERY_TOKEN,
                "--semantic.query.git-evidence.allowed-repositories[0]=" + REPOSITORY_ID, "--spring.main.banner-mode=off"));
        Path log = Files.createTempFile(temporaryDirectory, "semantic-review-journey-", ".log");
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(temporaryDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        processBuilder.environment().clear();
        assertColdQueryEnvironment(processBuilder.environment());
        return new RunningProcess(processBuilder.start(), log);
    }

    private RunningProcess start(Path jar, List<String> arguments) throws IOException {
        Path log = Files.createTempFile(temporaryDirectory, "semantic-review-journey-", ".log");
        Process process = new ProcessBuilder(command(jar, arguments)).directory(temporaryDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        return new RunningProcess(process, log);
    }

    private static List<String> command(Path jar, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(arguments);
        return command;
    }

    private static void assertColdQueryEnvironment(Map<String, String> environment) {
        assertThat(environment).doesNotContainKeys("JDTLS_HOME", "SEMANTIC_DATA_ROOT", "SEMANTIC_JDTLS_HOME",
                "SEMANTIC_JDTLS_WORKSPACE_DATA_ROOT", "SEMANTIC_INDEXER_ADMIN_TOKEN");
        assertThat(environment.keySet()).noneMatch(key -> key.startsWith("SEMANTIC_REPOSITORIES_")
                || key.startsWith("SEMANTIC_ANALYSIS_"));
    }

    private static Map<String, Object> reviewSearch(String reviewId, String side, String revision, String query, String kind) {
        return Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId, "side", side, "revision", revision,
                "query", query, "kinds", List.of(kind));
    }

    private String methodFact(String base, String reviewId, String side, String revision, String owner, JsonMapper mapper) throws Exception {
        Map<?, ?> response = successful(post(base, "/api/v1/reviews/search-code", QUERY_TOKEN,
                reviewSearch(reviewId, side, revision, "pay", "METHOD")), mapper);
        return mapList(map(response, "result"), "items").stream().filter(item -> String.valueOf(item.get("displayName")).contains(owner))
                .map(item -> text(item, "factId")).findFirst().orElseThrow();
    }

    private void assertMcpJourney(String base, String reviewId, String revisionA, String revisionB, String comparisonId,
                                  String legacyGateway, String legacyPay, String modernGateway, String modernPay, JsonMapper mapper) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header("X-Api-Token", QUERY_TOKEN)).build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(30)).build()) {
            assertThat(client.initialize().serverInfo()).isNotNull();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(TOOL_NAMES);
            Map<?, ?> review = mcpBody(client, "get_review", Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId), mapper);
            assertThat(text(map(review, "a"), "revision")).isEqualTo(revisionA);
            Map<?, ?> legacySearch = mcpBody(client, "review_search_code", reviewSearch(reviewId, "A", revisionA, "LegacyGateway", "TYPE"), mapper);
            assertThat(mapList(map(legacySearch, "result"), "items")).isNotEmpty();
            Map<?, ?> legacySource = mcpBody(client, "review_get_fact_source", Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId,
                    "side", "A", "revision", revisionA, "factId", legacyGateway), mapper);
            assertThat(text(map(map(legacySource, "result"), "source"), "code")).contains("LegacyGateway");
            Map<?, ?> legacyCallers = mcpBody(client, "review_find_callers", Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId,
                    "side", "A", "revision", revisionA, "methodFactId", legacyPay), mapper);
            assertThat(mapList(map(legacyCallers, "result"), "items")).isNotEmpty();
            Map<?, ?> search = mcpBody(client, "review_search_code", reviewSearch(reviewId, "B", revisionB, "ModernGateway", "TYPE"), mapper);
            assertThat(mapList(map(search, "result"), "items")).isNotEmpty();
            Map<?, ?> source = mcpBody(client, "review_get_fact_source", Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId,
                    "side", "B", "revision", revisionB, "factId", modernGateway), mapper);
            assertThat(text(map(map(source, "result"), "source"), "code")).contains("ModernGateway");
            Map<?, ?> callers = mcpBody(client, "review_find_callers", Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId,
                    "side", "B", "revision", revisionB, "methodFactId", modernPay), mapper);
            assertThat(mapList(map(callers, "result"), "items")).isNotEmpty();
            Map<?, ?> comparison = mcpBody(client, "compare_revisions", Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId,
                    "previous", revisionA, "current", revisionB), mapper);
            Map<?, ?> checkoutChange = mapList(comparison, "items").stream()
                    .filter(item -> "src/main/java/example/Checkout.java".equals(item.get("newPath"))).findFirst().orElseThrow();
            Map<?, ?> patch = mcpBody(client, "get_file_diff", Map.of("repositoryId", REPOSITORY_ID, "comparisonId", comparisonId,
                    "previous", revisionA, "current", revisionB, "changeId", text(checkoutChange, "changeId")), mapper);
            assertThat(text(patch, "patch")).contains("LegacyGateway", "ModernGateway");
        }
    }

    private static Map<?, ?> mcpBody(McpSyncClient client, String name, Map<String, Object> arguments, JsonMapper mapper) {
        McpSchema.CallToolResult response = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        assertThat(response.isError()).as("%s failed: %s", name, response.content()).isFalse();
        return mapper.convertValue(response.structuredContent(), Map.class);
    }

    private Map<?, ?> completed(String base, String jobId, JsonMapper mapper, GenericContainer<?> indexer) throws Exception {
        Instant deadline = Instant.now().plus(JOB_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get(base, "/index/repositories/" + REPOSITORY_ID + "/jobs/" + jobId, ADMIN_TOKEN);
            if (response.statusCode() == 200) {
                Map<?, ?> status = mapper.readValue(response.body(), Map.class);
                if ("COMPLETE".equals(status.get("phase"))) {
                    return status;
                }
                assertThat(status.get("phase")).as("%s%n%s", response.body(), indexer.getLogs()).isNotEqualTo("FAILED");
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("job did not complete: " + jobId + System.lineSeparator() + indexer.getLogs());
    }

    private void awaitHttp(String url, String token, GenericContainer<?> indexer) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!indexer.isRunning()) {
                throw new AssertionError("application terminated during startup: " + indexer.getLogs());
            }
            try {
                if (getUri(url, token).statusCode() < 500) {
                    return;
                }
            } catch (IOException exception) {
                // The application has not opened its local socket yet.
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("application did not open its local HTTP endpoint: " + indexer.getLogs());
    }

    private void awaitHttp(String url, String token, RunningProcess process) throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.process().isAlive()) {
                throw new AssertionError("application terminated during startup: " + Files.readString(process.log()));
            }
            try {
                if (getUri(url, token).statusCode() < 500) {
                    return;
                }
            } catch (IOException exception) {
                // The application has not opened its local socket yet.
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("application did not open its local HTTP endpoint: " + Files.readString(process.log()));
    }

    private static AssertionError withIndexerLogs(AssertionError failure, String logs) {
        return new AssertionError("Indexer container logs:" + System.lineSeparator() + logs + System.lineSeparator()
                + "Journey assertion: " + failure.getMessage(), failure);
    }

    private static HttpResponse<String> post(String base, String path, String token, Map<String, Object> body) throws Exception {
        return postUri(base + path, token, JsonMapper.builder().build().writeValueAsString(body));
    }

    private static HttpResponse<String> postUri(String uri, String token, String body) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(uri)).header("X-Api-Token", token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String base, String path, String token) throws IOException, InterruptedException {
        return getUri(base + path, token);
    }

    private static HttpResponse<String> getUri(String uri, String token) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(uri)).header("X-Api-Token", token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String accepted(HttpResponse<String> response, JsonMapper mapper) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return text(mapper.readValue(response.body(), Map.class), "jobId");
    }

    private static Map<?, ?> acceptedReview(HttpResponse<String> response, JsonMapper mapper) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        Map<?, ?> body = mapper.readValue(response.body(), Map.class);
        assertReviewComparisonType(body);
        return body;
    }

    private static void assertReviewComparisonType(Map<?, ?> response) {
        Map<?, ?> review = map(response, "review");
        assertThat(text(review, "comparisonType")).isEqualTo("CURRENT_TO_COMMIT");
        assertThat(response.containsKey("comparisonType")).isFalse();
    }

    private static Map<?, ?> successful(HttpResponse<String> response, JsonMapper mapper) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readValue(response.body(), Map.class);
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

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static void push(Git seed) throws Exception {
        seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record RunningProcess(Process process, Path log) implements AutoCloseable {
        private int await(Duration timeout) throws InterruptedException {
            assertThat(process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
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
