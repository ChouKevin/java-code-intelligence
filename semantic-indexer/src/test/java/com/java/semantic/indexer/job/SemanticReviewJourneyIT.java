package com.java.semantic.indexer.job;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
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
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Assumptions;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private HttpClient http;

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishes_real_jdt_review_evidence_then_serves_http_and_mcp_from_read_only_cold_mongo() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("semantic.review.journey.enabled"),
                "the external process journey is opt-in and requires fresh executable jars and an Indexer image");
        Path indexerJar = requiredJar("semantic.review.journey.indexer.jar");
        Path queryJar = requiredJar("semantic.review.journey.query.jar");
        Path remotePath = temporaryDirectory.resolve("semantic-review-remote.git");
        Path seedPath = temporaryDirectory.resolve("semantic-review-seed");
        int queryPort = availablePort();
        String indexerImage = requiredImage(INDEXER_IMAGE_PROPERTY);
        String reviewId;
        String changedInstallationReviewId;
        String baselineGeneration;
        String indexerLogs;

        try (HttpClient journeyHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
             Network network = Network.newNetwork();
             MongoDBContainer mongo = authenticatedMongo(network);
             Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            http = journeyHttp;
            mongo.start();
            String maintenanceUri = mongoUri(mongo, "root", "root-password", DATABASE, "admin");
            bootstrapSchema(indexerJar, maintenanceUri);
            createRuntimeUsers(maintenanceUri);
            String writerUri = mongoUri(mongo, "review-writer", "write-password", DATABASE, DATABASE);
            assertNoMaintenance(writerUri);
            String readerUri = mongoUri(mongo, "review-reader", "read-password", DATABASE, DATABASE);
            assertReadOnly(readerUri);
            String revisionA = commitA(seed, seedPath, remotePath);
            assertCleanMavenSeed(seedPath);
            remote.getRepository().updateRef(Constants.HEAD, true).link(Constants.R_HEADS + "main");
            String indexerMongoUri = mongoUri(MONGO_NETWORK_ALIAS, MONGO_CONTAINER_PORT,
                    "review-writer", "write-password", DATABASE, DATABASE);
            JsonMapper mapper = JsonMapper.builder().build();
            try (RunningProcess liveQuery = startQuery(queryJar, readerUri, queryPort);
                 GenericContainer<?> indexer = startIndexer(indexerImage, indexerMongoUri, remotePath, network, false)) {
                try {
                    String indexerBase = "http://" + indexer.getHost() + ":" + indexer.getMappedPort(INDEXER_CONTAINER_PORT);
                    awaitHttp(indexerBase + "/index/repositories/" + REPOSITORY_ID + "/publication", ADMIN_TOKEN, indexer);
                    String liveBase = "http://127.0.0.1:" + queryPort;
                    awaitHttp(liveBase + "/api/v1/repositories", QUERY_TOKEN, liveQuery);
                    try (McpSyncClient preparation = nativeClient(indexerBase, ADMIN_TOKEN, mapper);
                         McpSyncClient reader = nativeClient(liveBase, QUERY_TOKEN, mapper)) {
                        preparation.initialize();
                        reader.initialize();
                        assertThat(preparation.listTools().tools()).extracting(McpSchema.Tool::name)
                                .containsExactlyInAnyOrder("refresh_repository_metadata", "prepare_codebase", "prepare_review", "get_job");
                        Map<?, ?> unindexed = parity(liveBase, reader, "get_context", "/api/v1/context", currentDiscovery(), mapper);
                        assertThat(text(unindexed, "state")).isEqualTo("UNINDEXED");
                        Map<?, ?> repositories = repositoryParity(liveBase, reader, mapper);
                        assertThat(mapList(repositories, "items")).singleElement().satisfies(item -> {
                            assertThat(text(item, "repositoryId")).isEqualTo(REPOSITORY_ID);
                            assertThat(item.get("configured")).isEqualTo(true);
                            assertThat(item.containsKey("publishedRevision")).isFalse();
                        });
                        String requestId = savedRequestId("build");
                        // Deliberately retain no accepted job identity: recover solely with the saved requestId.
                        mcpBody(preparation, "prepare_codebase", Map.of("repositoryId", REPOSITORY_ID, "requestId", requestId), mapper);
                        Map<?, ?> recovered = mcpBody(preparation, "get_job",
                                Map.of("repositoryId", REPOSITORY_ID, "requestId", requestId), mapper);
                        String checkoutJob = text(recovered, "jobId");
                        assertThat(text(recovered, "preparationBranch")).isEqualTo("main");
                        assertThat(text(map(recovered, "target"), "revision")).isEqualTo(revisionA);
                        assertThat(text(recovered, "requestId")).isEqualTo(requestId);
                    Map<?, ?> currentA = completedLookup(indexerBase, "requestId=" + requestId, mapper, indexer);
                    assertThat(text(currentA, "jobId")).isEqualTo(checkoutJob);
                    assertThat(text(currentA, "requestId")).isEqualTo(requestId);
                    assertThat(text(currentA, "operation")).isEqualTo("BUILD");
                    assertThat(mcpBody(preparation, "get_job",
                            Map.of("repositoryId", REPOSITORY_ID, "requestId", requestId), mapper)).isEqualTo(currentA);
                    Map<?, ?> currentPointerA = map(currentA, "currentPointer");
                    assertThat(text(currentPointerA, "revision")).isEqualTo(revisionA);
                    baselineGeneration = text(currentPointerA, "generationId");
                        assertThat(text(map(parity(liveBase, reader, "get_context", "/api/v1/context",
                                currentDiscovery(), mapper), "projectGuide"), "state")).isEqualTo("DISABLED");
                        assertSemanticMatrix(liveBase, reader, currentContext(revisionA), "LegacyGateway", mapper);
                        String revisionB = commitB(seed, seedPath);
                        Map<?, ?> notPrepared = parity(liveBase, reader, "get_context", "/api/v1/context",
                                Map.of("repositoryId", REPOSITORY_ID,
                                        "selector", Map.of("kind", "COMMIT", "revision", revisionB)), mapper);
                        assertThat(text(notPrepared, "state")).isEqualTo("NOT_PREPARED");
                    String unauthorizedRequestId = savedRequestId("unauthorized-review");
                    assertThat(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/reviews", QUERY_TOKEN,
                            Map.of("requestId", unauthorizedRequestId, "selection", Map.of("kind", "COMMIT", "revision", revisionB))).statusCode()).isEqualTo(401);
                    String reviewRequestId = savedRequestId("review");
                    Map<?, ?> acceptedReviewBody = acceptedReview(post(indexerBase,
                            "/index/repositories/" + REPOSITORY_ID + "/reviews", ADMIN_TOKEN,
                            Map.of("requestId", reviewRequestId, "selection", Map.of("kind", "COMMIT", "revision", revisionB))), mapper);
                    String reviewJob = text(acceptedReviewBody, "jobId");
                    Map<?, ?> admittedReview = successful(get(indexerBase,
                            "/index/repositories/" + REPOSITORY_ID + "/jobs?jobId=" + reviewJob, ADMIN_TOKEN), mapper);
                    assertThat(text(admittedReview, "jobId")).isEqualTo(reviewJob);
                    assertThat(text(admittedReview, "operation")).isEqualTo("REVIEW");
                    assertReviewComparisonType(admittedReview);
                    Map<?, ?> admittedReviewDetails = map(admittedReview, "review");
                    assertThat(text(map(admittedReviewDetails, "selection"), "revision")).isEqualTo(revisionB);
                    assertThat(map(admittedReview, "currentPointer")).isEqualTo(currentPointerA);

                    Map<?, ?> completeReview = completed(indexerBase, reviewJob, mapper, indexer);
                    assertReviewComparisonType(completeReview);
                    Map<?, ?> review = map(completeReview, "review");
                    reviewId = text(review, "reviewId");
                    Map<?, ?> resolved = map(review, "resolvedEndpoints");
                    assertThat(text(resolved, "beforeRevision")).isEqualTo(revisionA);
                    assertThat(text(resolved, "afterRevision")).isEqualTo(revisionB);
                    assertThat(text(resolved, "baselineRule")).isEqualTo("FIRST_PARENT");

                    assertThat(indexer.execInContainer("sh", "-c",
                            "printf 'changed installation\\n' > /opt/jdtls/reuse-fingerprint-marker").getExitCode()).isZero();
                    String changedInstallationRequestId = savedRequestId("changed-installation-review");
                    Map<?, ?> changedInstallationAdmission = acceptedReview(post(indexerBase,
                            "/index/repositories/" + REPOSITORY_ID + "/reviews", ADMIN_TOKEN,
                            Map.of("requestId", changedInstallationRequestId, "selection", Map.of("kind", "COMMIT", "revision", revisionB))), mapper);
                    Map<?, ?> changedInstallationComplete = completed(indexerBase,
                            text(changedInstallationAdmission, "jobId"), mapper, indexer);
                    changedInstallationReviewId = text(map(changedInstallationComplete, "review"), "reviewId");
                    assertThat(map(changedInstallationComplete, "currentPointer")).isEqualTo(currentPointerA);
                        assertThat(mcpBody(preparation, "get_job", Map.of("repositoryId", REPOSITORY_ID,
                                "requestId", requestId), mapper)).isEqualTo(currentA);
                        Map<?, ?> duplicate = mcpError(preparation, "prepare_codebase",
                                Map.of("repositoryId", REPOSITORY_ID, "requestId", requestId), mapper);
                        assertThat(text(duplicate, "code")).isEqualTo("REQUEST_ID_REUSED");
                        assertThat(text(duplicate, "jobId")).isEqualTo(checkoutJob);
                        errorParity(indexerBase, preparation, "prepare_codebase",
                                "/index/repositories/" + REPOSITORY_ID + "/codebase",
                                Map.of("repositoryId", REPOSITORY_ID, "requestId", requestId),
                                Map.of("requestId", requestId), 409, "REQUEST_ID_REUSED", ADMIN_TOKEN, mapper);
                        Files.writeString(temporaryDirectory.resolve("review-revision"), revisionB);
                        Files.writeString(temporaryDirectory.resolve("baseline-result"), mapper.writeValueAsString(currentA));
                    }
                } catch (AssertionError failure) {
                    throw withIndexerLogs(failure, indexer.getLogs());
                } finally {
                    indexerLogs = indexer.getLogs();
                }
            }

            String revisionB = Files.readString(temporaryDirectory.resolve("review-revision"));
            String buildRequest = Files.readString(temporaryDirectory.resolve("build-request-id"));
            try (RunningProcess liveQuery = startQuery(queryJar, readerUri, queryPort);
                 GenericContainer<?> restarted = startIndexer(indexerImage, indexerMongoUri, remotePath, network, true)) {
                String base = "http://" + restarted.getHost() + ":" + restarted.getMappedPort(INDEXER_CONTAINER_PORT);
                String liveBase = "http://127.0.0.1:" + queryPort;
                awaitHttp(base + "/index/repositories/" + REPOSITORY_ID + "/publication", ADMIN_TOKEN, restarted);
                awaitHttp(liveBase + "/api/v1/repositories", QUERY_TOKEN, liveQuery);
                try (McpSyncClient preparation = nativeClient(base, ADMIN_TOKEN, mapper);
                     McpSyncClient reader = nativeClient(liveBase, QUERY_TOKEN, mapper)) {
                    preparation.initialize();
                    reader.initialize();
                    Map<?, ?> original = mcpBody(preparation, "get_job",
                            Map.of("repositoryId", REPOSITORY_ID, "requestId", buildRequest), mapper);
                    assertThat(original).isEqualTo(mapper.readValue(Files.readString(temporaryDirectory.resolve("baseline-result")), Map.class));
                    String guideText = validGuide(revisionB);
                    String revisionC = commitGuide(seed, seedPath, guideText, "valid guide");
                    String validGuideRequestId = savedRequestId("valid-guide-build");
                    Map<?, ?> admitted;
                    try {
                        Map<?, ?> armed = successful(post(base, "/index/uat/publication/arm", ADMIN_TOKEN, Map.of()), mapper);
                        admitted = mcpBody(preparation, "prepare_codebase",
                                Map.of("repositoryId", REPOSITORY_ID, "requestId", validGuideRequestId), mapper);
                        Map<?, ?> reached = successful(http.send(HttpRequest.newBuilder(
                                URI.create(base + "/index/uat/publication/await")).timeout(JOB_TIMEOUT.plusSeconds(10))
                                .header("X-Api-Token", ADMIN_TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString()), mapper);
                        assertThat(reached.get("cycleId")).isEqualTo(armed.get("cycleId"));
                        // Publication is held while CURRENT A, its source and immutable READY review are read.
                        Map<?, ?> during = successful(post(liveBase, "/api/v1/context", QUERY_TOKEN, currentDiscovery()), mapper);
                        assertThat(text(during, "revision")).isEqualTo(revisionA);
                        assertThat(text(map(during, "activeJob"), "operation")).isEqualTo("BUILD");
                        Map<?, ?> oldSource = successful(post(liveBase, "/api/v1/source", QUERY_TOKEN,
                                Map.of("context", currentContext(revisionA), "target",
                                        Map.of("kind", "FILE", "path", "src/main/java/example/LegacyGateway.java"))), mapper);
                        assertThat(text(oldSource, "content")).contains("class LegacyGateway");
                        assertThat(map(oldSource, "context")).isEqualTo(currentContext(revisionA));
                        assertSemanticMatrix(liveBase, reader, context(reviewId, "BEFORE", revisionA), "LegacyGateway", mapper);
                        assertSemanticMatrix(liveBase, reader, context(reviewId, "AFTER", revisionB), "ModernGateway", mapper);
                    } finally {
                        HttpResponse<String> released = post(base, "/index/uat/publication/release", ADMIN_TOKEN, Map.of());
                        assertThat(released.statusCode()).as(released.body()).isEqualTo(200);
                    }
                    completed(base, text(admitted, "jobId"), mapper, restarted);
                    assertSemanticMatrix(liveBase, reader, context(reviewId, "BEFORE", revisionA), "LegacyGateway", mapper);
                    assertSemanticMatrix(liveBase, reader, context(reviewId, "AFTER", revisionB), "ModernGateway", mapper);
                    Map<?, ?> valid = parity(liveBase, reader, "get_context", "/api/v1/context", currentDiscovery(), mapper);
                    assertThat(text(valid, "revision")).isEqualTo(revisionC);
                    assertThat(map(valid, "context")).isEqualTo(currentContext(revisionC));
                    assertThat(mapList(repositoryParity(liveBase, reader, mapper), "items")).singleElement()
                            .satisfies(item -> assertThat(text(item, "publishedRevision")).isEqualTo(revisionC));
                    assertGuide(liveBase, reader, valid, revisionB, revisionC, guideText, mapper);
                    errorParity(liveBase, reader, "search_code", "/api/v1/search-code",
                            Map.of("context", currentContext(revisionA), "query", "LegacyGateway"),
                            Map.of("context", currentContext(revisionA), "query", "LegacyGateway"),
                            409, "REVISION_OUTDATED", QUERY_TOKEN, mapper);
                    String revisionD = commitGuide(seed, seedPath, "# Invalid guide\nGuideOnlySentinel\n", "invalid guide");
                    String invalidGuideRequestId = savedRequestId("invalid-guide-build");
                    Map<?, ?> invalidAdmission = mcpBody(preparation, "prepare_codebase",
                            Map.of("repositoryId", REPOSITORY_ID, "requestId", invalidGuideRequestId), mapper);
                    completed(base, text(invalidAdmission, "jobId"), mapper, restarted);
                    Map<?, ?> invalid = parity(liveBase, reader, "get_context", "/api/v1/context", currentDiscovery(), mapper);
                    assertThat(text(invalid, "revision")).isEqualTo(revisionD);
                    assertThat(text(map(invalid, "projectGuide"), "state")).isEqualTo("INVALID");
                    assertSemanticMatrix(liveBase, reader, currentContext(revisionD), "ModernGateway", mapper);
                    assertUnavailableDocument(liveBase, reader, currentContext(revisionD), "PROJECT_GUIDE.md", mapper);
                    assertGuideExcludedFromSearch(liveBase, reader, currentContext(revisionD), mapper);
                    Map<?, ?> recoveredAfterMovement = mcpBody(preparation, "get_job",
                            Map.of("repositoryId", REPOSITORY_ID, "requestId", buildRequest), mapper);
                    assertSameJob(recoveredAfterMovement, original);
                    Map<?, ?> httpRecovered = successful(get(base, "/index/repositories/" + REPOSITORY_ID
                            + "/jobs?requestId=" + buildRequest, ADMIN_TOKEN), mapper);
                    assertThat(httpRecovered).isEqualTo(recoveredAfterMovement);
                    Files.writeString(temporaryDirectory.resolve("current-revision"), revisionD);
                }
            }
            try {
                Files.move(remotePath, temporaryDirectory.resolve("remote-unavailable"));
                Files.move(seedPath, temporaryDirectory.resolve("seed-unavailable"));

                RunningProcess query = startQuery(queryJar, readerUri, queryPort);
                try {
                    String queryBase = "http://127.0.0.1:" + queryPort;
                    awaitHttp(queryBase + "/api/v1/repositories", QUERY_TOKEN, query);
                    Map<?, ?> reviewDetails = successful(post(queryBase, "/api/v1/context", QUERY_TOKEN, discoveryRequest(reviewId)), mapper);
                    Map<?, ?> before = map(reviewDetails, "before");
                    Map<?, ?> after = map(reviewDetails, "after");
                    assertThat(text(before, "revision")).isEqualTo(revisionA);
                    assertThat(text(after, "revision")).isEqualTo(revisionB);
                    assertThat(map(before, "context")).isEqualTo(context(reviewId, "BEFORE", revisionA));
                    assertThat(map(after, "context")).isEqualTo(context(reviewId, "AFTER", revisionB));
                    try (McpSyncClient reader = nativeClient(queryBase, QUERY_TOKEN, mapper)) {
                        reader.initialize();
                        Map<?, ?> coldCurrent = parity(queryBase, reader, "get_context", "/api/v1/context", currentDiscovery(), mapper);
                        assertThat(text(coldCurrent, "revision")).isEqualTo(Files.readString(temporaryDirectory.resolve("current-revision")));
                        assertThat(text(map(coldCurrent, "projectGuide"), "state")).isEqualTo("INVALID");
                        assertUnavailableDocument(queryBase, reader, currentContext(text(coldCurrent, "revision")), "PROJECT_GUIDE.md", mapper);
                        assertSemanticMatrix(queryBase, reader, context(reviewId, "BEFORE", revisionA), "LegacyGateway", mapper);
                        assertSemanticMatrix(queryBase, reader, context(reviewId, "AFTER", revisionB), "ModernGateway", mapper);
                        assertSemanticMatrix(queryBase, reader, currentContext(Files.readString(
                                temporaryDirectory.resolve("current-revision"))), "ModernGateway", mapper);
                    }
                    try (MongoClient evidenceClient = MongoClients.create(writerUri)) {
                        MongoCollection<Document> manifests = evidenceClient.getDatabase(DATABASE).getCollection("review_manifests");
                        Document original = manifests.find(new Document("reviewId", reviewId)).first();
                        Document changed = manifests.find(new Document("reviewId", changedInstallationReviewId)).first();
                        Document originalGeneration = original.get("before", Document.class).get("generation", Document.class);
                        Document changedGeneration = changed.get("before", Document.class).get("generation", Document.class);
                        assertThat(originalGeneration.get("selected", Document.class)
                                .get("generationId", Document.class).getString("value")).isEqualTo(baselineGeneration);
                        assertThat(changedGeneration.get("selected", Document.class)
                                .get("generationId", Document.class).getString("value")).isNotEqualTo(baselineGeneration);
                        assertThat(changedGeneration.get("fingerprint", Document.class))
                                .isNotEqualTo(originalGeneration.get("fingerprint", Document.class));
                    }

                    Map<String, Object> searchA = reviewSearch(reviewId, "BEFORE", revisionA, "LegacyGateway", "TYPE");
                    Map<?, ?> legacySearch = successful(post(queryBase, "/api/v1/search-code", QUERY_TOKEN, searchA), mapper);
                    String legacyGateway = text(mapList(legacySearch, "items").getFirst(), "factId");
                    Map<?, ?> legacySource = successful(post(queryBase, "/api/v1/source", QUERY_TOKEN,
                            factSource(context(reviewId, "BEFORE", revisionA), legacyGateway)), mapper);
                    assertThat(text(legacySource, "content")).contains("LegacyGateway");
                    String legacyPay = methodFact(queryBase, reviewId, "BEFORE", revisionA, "LegacyGateway", mapper);
                    Map<?, ?> legacyCallers = successful(post(queryBase, "/api/v1/relations", QUERY_TOKEN,
                            callers(context(reviewId, "BEFORE", revisionA), legacyPay)), mapper);
                    assertThat(mapList(legacyCallers, "items")).anySatisfy(item ->
                            assertThat(text(map(item, "origin"), "canonical")).contains("Checkout"));

                    Map<String, Object> searchB = reviewSearch(reviewId, "AFTER", revisionB, "ModernGateway", "TYPE");
                    Map<?, ?> searchResult = successful(post(queryBase, "/api/v1/search-code", QUERY_TOKEN, searchB), mapper);
                    String modernGateway = text(mapList(searchResult, "items").getFirst(), "factId");
                    Map<?, ?> source = successful(post(queryBase, "/api/v1/source", QUERY_TOKEN,
                            factSource(context(reviewId, "AFTER", revisionB), modernGateway)), mapper);
                    assertThat(text(source, "content")).contains("ModernGateway");
                    String modernPay = methodFact(queryBase, reviewId, "AFTER", revisionB, "ModernGateway", mapper);
                    Map<?, ?> callers = successful(post(queryBase, "/api/v1/relations", QUERY_TOKEN,
                            callers(context(reviewId, "AFTER", revisionB), modernPay)), mapper);
                    assertThat(mapList(callers, "items")).anySatisfy(item ->
                            assertThat(text(map(item, "origin"), "canonical")).contains("Checkout"));
                    Map<?, ?> comparisonContext = map(reviewDetails, "comparisonContext");
                    Map<?, ?> comparison = successful(post(queryBase, "/api/v1/git/comparisons", QUERY_TOKEN,
                            Map.of("comparisonContext", comparisonContext)), mapper);
                    assertThat(((Number) map(comparison, "policyCoverage").get("excludedChanges")).longValue()).isZero();
                    assertThat(mapList(map(comparison, "policyCoverage"), "reasons")).isEmpty();
                    Map<?, ?> checkoutChange = mapList(comparison, "items").stream()
                            .filter(item -> "src/main/java/example/Checkout.java".equals(map(item, "after").get("path"))).findFirst().orElseThrow();
                    Map<?, ?> patch = successful(post(queryBase, "/api/v1/git/file-diff", QUERY_TOKEN,
                            Map.of("comparisonContext", comparisonContext, "changeId", text(checkoutChange, "changeId"))), mapper);
                    assertThat(text(patch, "patch")).contains("LegacyGateway", "ModernGateway");
                    assertMcpJourney(queryBase, reviewId, revisionA, revisionB, comparisonContext, legacyGateway, legacyPay, modernGateway, modernPay, mapper);
                } finally {
                    query.close();
                }
            } catch (AssertionError failure) {
                throw withIndexerLogs(failure, indexerLogs);
            }
        }
    }

    private String savedRequestId(String label) throws IOException {
        String requestId = UUID.randomUUID().toString();
        Files.writeString(temporaryDirectory.resolve(label + "-request-id"), requestId);
        return requestId;
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

    private static void createRuntimeUsers(String maintenanceUri) {
        try (MongoClient client = MongoClients.create(maintenanceUri)) {
            MongoDatabase database = client.getDatabase(DATABASE);
            List<Document> privileges = List.of(new Document("resource", new Document("db", DATABASE).append("collection", ""))
                    .append("actions", List.of("find", "insert", "update", "remove", "listCollections", "listIndexes")));
            database.runCommand(new Document("createRole", "runtime-writer").append("privileges", privileges).append("roles", List.of()));
            database.runCommand(new Document("createUser", "review-writer").append("pwd", "write-password")
                    .append("roles", List.of(new Document("role", "runtime-writer").append("db", DATABASE))));
            database.runCommand(new Document("createUser", "review-reader").append("pwd", "read-password")
                    .append("roles", List.of(new Document("role", "read").append("db", DATABASE))));
        }
    }

    private static void assertNoMaintenance(String writerUri) {
        try (MongoClient client = MongoClients.create(writerUri)) {
            assertThatThrownBy(() -> client.getDatabase(DATABASE).runCommand(new Document("collMod", "repositories")
                    .append("validationLevel", "strict"))).isInstanceOf(MongoCommandException.class);
        }
    }

    private static void assertReadOnly(String readerUri) {
        try (MongoClient client = MongoClients.create(readerUri)) {
            assertThatThrownBy(() -> client.getDatabase(DATABASE)
                    .getCollection("read_only_probe").insertOne(new Document("probe", true)))
                    .isInstanceOf(MongoCommandException.class);
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
        write(root, "src/main/resources/mapper/PaymentMapper.xml", """
                <mapper namespace="example.PaymentMapper">
                  <select id="findActive">SELECT id FROM payments WHERE active = TRUE</select>
                </mapper>
                """);
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

    private GenericContainer<?> startIndexer(String image, String mongoUri, Path remote, Network network, boolean guide) {
        GenericContainer<?> indexer = new GenericContainer<>(DockerImageName.parse(image))
                .withNetwork(network)
                .withExposedPorts(INDEXER_CONTAINER_PORT)
                .withTmpFs(Map.of("/data/repos", "rw,noexec,nosuid,nodev,size=1g", "/data/jdtls", "rw,noexec,nosuid,nodev,size=1g"))
                .withFileSystemBind(remote.toAbsolutePath().toString(), REMOTE_CONTAINER_PATH, BindMode.READ_ONLY)
                .withCommand(indexerArguments(mongoUri, guide));
        indexer.start();
        return indexer;
    }

    private static String[] indexerArguments(String mongoUri, boolean guide) {
        List<String> arguments = new ArrayList<>(List.of("--spring.mongodb.uri=" + mongoUri, "--server.address=0.0.0.0",
                "--server.port=" + INDEXER_CONTAINER_PORT, "--semantic.indexer.admin-token=" + ADMIN_TOKEN,
                "--semantic.repositories." + REPOSITORY_ID + ".url=file://" + REMOTE_CONTAINER_PATH,
                "--semantic.repositories." + REPOSITORY_ID + ".default-branch=main",
                "--semantic.index-jobs.poll-delay=20ms", "--spring.main.banner-mode=off"));
        if (guide) {
            arguments.add("--semantic.repositories." + REPOSITORY_ID + ".project-guide-path=PROJECT_GUIDE.md");
            // Only this controlled opt-in container exposes the existing UAT publication barrier.
            arguments.add("--spring.profiles.active=uat");
            arguments.add("--semantic.uat.publication-timeout=" + JOB_TIMEOUT.toSeconds() + "s");
        }
        return arguments.toArray(String[]::new);
    }

    private static McpSyncClient nativeClient(String base, String token, JsonMapper mapper) {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder(base + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header("X-Api-Token", token)).build())
                .requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(30)).build();
    }

    private static Map<String, Object> currentDiscovery() {
        return Map.of("repositoryId", REPOSITORY_ID, "selector", Map.of("kind", "CURRENT"));
    }

    private static Map<String, Object> currentContext(String revision) {
        return Map.of("kind", "CURRENT", "repositoryId", REPOSITORY_ID, "revision", revision);
    }

    private Map<?, ?> repositoryParity(String base, McpSyncClient client, JsonMapper mapper) throws Exception {
        Map<?, ?> http = successful(get(base, "/api/v1/repositories", QUERY_TOKEN), mapper);
        assertThat(mcpBody(client, "list_repositories", Map.of(), mapper)).isEqualTo(http);
        return http;
    }

    private Map<?, ?> parity(String base, McpSyncClient client, String tool, String route,
            Map<String, Object> request, JsonMapper mapper) throws Exception {
        Map<?, ?> http = successful(post(base, route, QUERY_TOKEN, request), mapper);
        assertThat(mcpBody(client, tool, request, mapper)).isEqualTo(http);
        return http;
    }

    private static Map<?, ?> mcpError(McpSyncClient client, String tool, Map<String, Object> arguments, JsonMapper mapper) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        assertThat(result.isError()).isTrue();
        assertThat(mapper.readTree(((McpSchema.TextContent) result.content().getFirst()).text()))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(result.structuredContent())));
        return mapper.convertValue(result.structuredContent(), Map.class);
    }

    private void errorParity(String base, McpSyncClient client, String tool, String route,
            Map<String, Object> arguments, Map<String, Object> httpArguments, int status, String code,
            String token, JsonMapper mapper) throws Exception {
        Map<?, ?> error = mcpError(client, tool, arguments, mapper);
        assertThat(text(error, "code")).isEqualTo(code);
        HttpResponse<String> http = post(base, route, token, httpArguments);
        assertThat(http.statusCode()).as(http.body()).isEqualTo(status);
        Map<?, ?> httpError = mapper.readValue(http.body(), Map.class);
        assertThat(httpError).isEqualTo(error);
    }

    private void assertSemanticMatrix(String base, McpSyncClient client, Map<String, Object> readContext,
            String gateway, JsonMapper mapper) throws Exception {
        Map<?, ?> search = parity(base, client, "search_code", "/api/v1/search-code",
                Map.of("context", readContext, "query", gateway), mapper);
        assertThat(map(search, "context")).isEqualTo(readContext);
        Map<?, ?> fact = mapList(search, "items").stream()
                .filter(item -> "TYPE".equals(item.get("kind")) && gateway.equals(item.get("displayName"))).findFirst().orElseThrow();
        String factId = text(fact, "factId");
        Map<?, ?> source = parity(base, client, "read_source", "/api/v1/source", factSource(readContext, factId), mapper);
        assertThat(text(source, "path")).isEqualTo("src/main/java/example/" + gateway + ".java");
        assertThat(text(source, "content")).contains("class " + gateway);
        assertThat(map(source, "factRange")).isEqualTo(map(fact, "range"));
        assertThat(map(source, "context")).isEqualTo(readContext);
        Map<?, ?> files = parity(base, client, "list_files", "/api/v1/files",
                Map.of("context", readContext, "directory", "src/main/java/example"), mapper);
        assertThat(mapList(files, "items")).anySatisfy(item ->
                assertThat(text(item, "path")).isEqualTo("src/main/java/example/" + gateway + ".java"));
        Map<?, ?> textResult = parity(base, client, "search_text", "/api/v1/search-text",
                Map.of("context", readContext, "query", "new " + gateway, "directory", "src/main/java"), mapper);
        assertThat(mapList(textResult, "items")).singleElement().satisfies(item -> {
            assertThat(text(item, "path")).isEqualTo("src/main/java/example/Checkout.java");
            assertThat(text(item, "snippet")).contains("new " + gateway + "().pay()");
            assertThat(map(map(item, "range"), "start").get("line")).isEqualTo(0);
        });
        Map<?, ?> entries = parity(base, client, "list_entry_points", "/api/v1/entry-points",
                Map.of("context", readContext), mapper);
        assertThat(mapList(entries, "items")).isEmpty();
        Map<?, ?> outline = parity(base, client, "get_outline", "/api/v1/outline",
                Map.of("context", readContext, "target", Map.of("kind", "TYPE", "factId", factId)), mapper);
        Map<?, ?> pay = mapList(outline, "items").stream().filter(item -> "METHOD".equals(item.get("kind"))
                && "pay".equals(item.get("displayName"))).findFirst().orElseThrow();
        Map<?, ?> relations = parity(base, client, "find_relations", "/api/v1/relations",
                callers(readContext, text(pay, "factId")), mapper);
        assertThat(mapList(relations, "items")).anySatisfy(item -> {
            assertThat(text(map(item, "origin"), "canonical")).contains("Checkout");
            assertThat(text(map(item, "occurrence"), "path")).isEqualTo("src/main/java/example/Checkout.java");
        });
        Map<String, Object> wrongKind = Map.of("context", readContext, "target", Map.of("kind", "TYPE", "factId", text(pay, "factId")));
        errorParity(base, client, "get_outline", "/api/v1/outline", wrongKind, wrongKind,
                400, "FACT_KIND_MISMATCH", QUERY_TOKEN, mapper);
        Map<String, Object> invalidSearch = Map.of("context", readContext, "query", "x");
        errorParity(base, client, "search_code", "/api/v1/search-code", invalidSearch, invalidSearch,
                400, "INVALID_ARGUMENT", QUERY_TOKEN, mapper);
        if ("REVIEW".equals(readContext.get("kind"))) {
            Map<String, Object> mismatch = new java.util.HashMap<>(readContext);
            mismatch.put("revision", "0".repeat(40));
            Map<String, Object> request = Map.of("context", mismatch, "query", gateway);
            errorParity(base, client, "search_code", "/api/v1/search-code", request, request,
                    409, "REVIEW_CONTEXT_MISMATCH", QUERY_TOKEN, mapper);
            Map<?, ?> discovery = parity(base, client, "get_context", "/api/v1/context",
                    discoveryRequest((String) readContext.get("reviewId")), mapper);
            assertThat(text(discovery, "state")).isEqualTo("READY");
            assertThat(map(map(discovery, "BEFORE".equals(readContext.get("side")) ? "before" : "after"), "context"))
                    .isEqualTo(readContext);
        }
    }

    private String commitGuide(Git seed, Path root, String content, String message) throws Exception {
        write(root, "PROJECT_GUIDE.md", content);
        write(root, "OTHER.md", "# Not opted in\nOtherDocumentSentinel\n");
        seed.add().addFilepattern(".").call();
        String revision = seed.commit().setMessage(message).setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").call().getId().name();
        push(seed);
        return revision;
    }

    private static String validGuide(String analyzedRevision) {
        return """
                # Project guide
                ```json
                {"formatVersion":1,"promptVersion":1,"repositoryId":"%s","analyzedRevision":"%s",
                 "generatedAt":"2026-09-28T00:00:00Z","sourceScope":{"includedPaths":["src/main/java"],
                 "excludedPaths":[],"limitations":["No runtime verification"]}}
                ```
                GuideOnlySentinel
                """.formatted(REPOSITORY_ID, analyzedRevision);
    }

    private void assertGuide(String base, McpSyncClient reader, Map<?, ?> discovery, String analyzedRevision,
            String importedRevision, String guideText, JsonMapper mapper) throws Exception {
        Map<?, ?> guide = map(discovery, "projectGuide");
        assertThat(text(guide, "state")).isEqualTo("AVAILABLE");
        assertThat(text(guide, "importedRevision")).isEqualTo(importedRevision);
        assertThat(text(map(guide, "provenance"), "analyzedRevision")).isEqualTo(analyzedRevision);
        assertThat(text(guide, "freshness")).isEqualTo("NOT_VERIFIED");
        assertThat(text(guide, "digest")).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(guideText.getBytes(StandardCharsets.UTF_8))));
        Map<String, Object> readContext = currentContext(importedRevision);
        Map<?, ?> source = parity(base, reader, "read_source", "/api/v1/source",
                Map.of("context", readContext, "target", Map.of("kind", "FILE", "path", "PROJECT_GUIDE.md")), mapper);
        assertThat(text(source, "content")).isEqualTo(guideText);
        assertThat(map(source, "projectGuide")).isEqualTo(guide);
        Map<?, ?> files = parity(base, reader, "list_files", "/api/v1/files", Map.of("context", readContext), mapper);
        assertThat(mapList(files, "items")).anySatisfy(item -> {
            assertThat(text(item, "path")).isEqualTo("PROJECT_GUIDE.md");
            assertThat(text(item, "contentKind")).isEqualTo("PROJECT_GUIDE");
        }).noneSatisfy(item -> assertThat(text(item, "path")).isEqualTo("OTHER.md"));
        assertGuideExcludedFromSearch(base, reader, readContext, mapper);
        assertUnavailableDocument(base, reader, readContext, "OTHER.md", mapper);
    }

    private void assertGuideExcludedFromSearch(String base, McpSyncClient reader, Map<String, Object> readContext,
            JsonMapper mapper) throws Exception {
        for (String tool : List.of("search_code", "search_text")) {
            Map<?, ?> result = parity(base, reader, tool, tool.equals("search_code") ? "/api/v1/search-code" : "/api/v1/search-text",
                    Map.of("context", readContext, "query", "GuideOnlySentinel"), mapper);
            assertThat(mapList(result, "items")).isEmpty();
        }
    }

    private void assertUnavailableDocument(String base, McpSyncClient reader, Map<String, Object> readContext,
            String path, JsonMapper mapper) throws Exception {
        Map<String, Object> request = Map.of("context", readContext, "target", Map.of("kind", "FILE", "path", path));
        errorParity(base, reader, "read_source", "/api/v1/source", request, request,
                404, "GIT_EVIDENCE_NOT_FOUND", QUERY_TOKEN, mapper);
    }

    private static void assertSameJob(Map<?, ?> actual, Map<?, ?> expected) {
        for (String field : List.of("jobId", "requestId", "repositoryId", "operation", "phase", "requested", "target", "preparationBranch")) {
            assertThat(actual.get(field)).as("original job %s", field).isEqualTo(expected.get(field));
        }
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

    private static Map<String, Object> discoveryRequest(String reviewId) {
        return Map.of("repositoryId", REPOSITORY_ID, "selector", Map.of("kind", "REVIEW", "reviewId", reviewId));
    }

    private static Map<String, Object> context(String reviewId, String side, String revision) {
        return Map.of("kind", "REVIEW", "repositoryId", REPOSITORY_ID, "reviewId", reviewId, "side", side, "revision", revision);
    }

    private static Map<String, Object> factSource(Map<String, Object> context, String factId) {
        return Map.of("context", context, "target", Map.of("kind", "FACT", "factId", factId));
    }

    private static Map<String, Object> callers(Map<String, Object> context, String factId) {
        return Map.of("context", context, "relation", "CALLERS", "factId", factId);
    }

    private static Map<String, Object> reviewSearch(String reviewId, String side, String revision, String query, String kind) {
        return Map.of("context", context(reviewId, side, revision), "query", query, "kinds", List.of(kind));
    }

    private String methodFact(String base, String reviewId, String side, String revision, String owner, JsonMapper mapper) throws Exception {
        Map<?, ?> response = successful(post(base, "/api/v1/search-code", QUERY_TOKEN,
                reviewSearch(reviewId, side, revision, "pay", "METHOD")), mapper);
        return mapList(response, "items").stream().filter(item -> String.valueOf(item.get("canonical")).contains(owner))
                .map(item -> text(item, "factId")).findFirst().orElseThrow();
    }

    private void assertMcpJourney(String base, String reviewId, String revisionA, String revisionB, Map<?, ?> comparisonContext,
            String legacyGateway, String legacyPay, String modernGateway, String modernPay, JsonMapper mapper) throws Exception {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header("X-Api-Token", QUERY_TOKEN)).build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(30)).build()) {
            client.initialize();
            Map<?, ?> review = mcpBody(client, "get_context", discoveryRequest(reviewId), mapper);
            assertThat(text(map(review, "before"), "revision")).isEqualTo(revisionA);
            Map<?, ?> legacySource = mcpBody(client, "read_source", factSource(context(reviewId, "BEFORE", revisionA), legacyGateway), mapper);
            assertThat(text(legacySource, "content")).contains("LegacyGateway");
            Map<?, ?> legacyCallers = mcpBody(client, "find_relations", callers(context(reviewId, "BEFORE", revisionA), legacyPay), mapper);
            assertThat(mapList(legacyCallers, "items")).anySatisfy(item -> assertThat(text(map(item, "origin"), "canonical")).contains("Checkout"));
            Map<?, ?> source = mcpBody(client, "read_source", factSource(context(reviewId, "AFTER", revisionB), modernGateway), mapper);
            assertThat(text(source, "content")).contains("ModernGateway");
            Map<?, ?> callers = mcpBody(client, "find_relations", callers(context(reviewId, "AFTER", revisionB), modernPay), mapper);
            assertThat(mapList(callers, "items")).anySatisfy(item -> assertThat(text(map(item, "origin"), "canonical")).contains("Checkout"));
            Map<?, ?> comparison = mcpBody(client, "compare_revisions", Map.of("comparisonContext", comparisonContext), mapper);
            assertThat(comparison).isEqualTo(successful(post(base, "/api/v1/git/comparisons", QUERY_TOKEN,
                    Map.of("comparisonContext", comparisonContext)), mapper));
            Map<?, ?> checkoutChange = mapList(comparison, "items").stream()
                    .filter(item -> "src/main/java/example/Checkout.java".equals(map(item, "after").get("path"))).findFirst().orElseThrow();
            Map<?, ?> patch = mcpBody(client, "get_file_diff", Map.of("comparisonContext", comparisonContext, "changeId", text(checkoutChange, "changeId")), mapper);
            assertThat(patch).isEqualTo(successful(post(base, "/api/v1/git/file-diff", QUERY_TOKEN,
                    Map.of("comparisonContext", comparisonContext, "changeId", text(checkoutChange, "changeId"))), mapper));
            assertThat(text(patch, "patch")).contains("LegacyGateway", "ModernGateway");
            assertMapperVisibleThroughHttpAndMcp(base, client, reviewId, revisionA, revisionB, mapper);
        }
    }

    private void assertMapperVisibleThroughHttpAndMcp(String base, McpSyncClient client, String reviewId,
            String revisionA, String revisionB, JsonMapper mapper) throws Exception {
        Map<String, Object> currentContext = context(reviewId, "BEFORE", revisionA);
        Map<String, Object> currentSearchRequest = Map.of("context", currentContext, "query", "findActive", "kinds", List.of("MAPPER_STATEMENT"));
        Map<?, ?> currentSearch = successful(post(base, "/api/v1/search-code", QUERY_TOKEN, currentSearchRequest), mapper);
        assertThat(mapList(currentSearch, "items")).singleElement()
                .satisfies(item -> assertThat(text(item, "mapperStatementKind")).isEqualTo("SELECT"));
        String currentFactId = text(mapList(currentSearch, "items").getFirst(), "factId");
        Map<String, Object> currentSourceRequest = factSource(currentContext, currentFactId);
        Map<?, ?> currentSource = successful(post(base, "/api/v1/source", QUERY_TOKEN, currentSourceRequest), mapper);
        assertThat(text(currentSource, "content")).contains("SELECT id FROM payments WHERE active = TRUE");
        assertThat(mcpBody(client, "search_code", currentSearchRequest, mapper)).isEqualTo(currentSearch);
        assertThat(mcpBody(client, "read_source", currentSourceRequest, mapper)).isEqualTo(currentSource);
        Map<String, Object> reviewSearchRequest = reviewSearch(reviewId, "AFTER", revisionB, "findActive", "MAPPER_STATEMENT");
        Map<?, ?> reviewSearch = successful(post(base, "/api/v1/search-code", QUERY_TOKEN, reviewSearchRequest), mapper);
        assertThat(mapList(reviewSearch, "items")).singleElement()
                .satisfies(item -> assertThat(text(item, "mapperStatementKind")).isEqualTo("SELECT"));
        String reviewFactId = text(mapList(reviewSearch, "items").getFirst(), "factId");
        Map<String, Object> reviewSourceRequest = factSource(context(reviewId, "AFTER", revisionB), reviewFactId);
        Map<?, ?> reviewSource = successful(post(base, "/api/v1/source", QUERY_TOKEN, reviewSourceRequest), mapper);
        assertThat(text(reviewSource, "content")).contains("SELECT id FROM payments WHERE active = TRUE");
        assertThat(mcpBody(client, "search_code", reviewSearchRequest, mapper)).isEqualTo(reviewSearch);
        assertThat(mcpBody(client, "read_source", reviewSourceRequest, mapper)).isEqualTo(reviewSource);
    }

    private static Map<?, ?> mcpBody(McpSyncClient client, String name, Map<String, Object> arguments, JsonMapper mapper) {
        McpSchema.CallToolResult response = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        assertThat(response.isError()).as("%s failed: %s", name, response.content()).isFalse();
        assertThat(mapper.readTree(((McpSchema.TextContent) response.content().getFirst()).text()))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(response.structuredContent())));
        return mapper.convertValue(response.structuredContent(), Map.class);
    }

    private Map<?, ?> completed(String base, String jobId, JsonMapper mapper, GenericContainer<?> indexer) throws Exception {
        return completedLookup(base, "jobId=" + jobId, mapper, indexer);
    }

    private Map<?, ?> completedLookup(String base, String lookup, JsonMapper mapper, GenericContainer<?> indexer) throws Exception {
        Instant deadline = Instant.now().plus(JOB_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get(base, "/index/repositories/" + REPOSITORY_ID + "/jobs?" + lookup, ADMIN_TOKEN);
            if (response.statusCode() == 200) {
                Map<?, ?> status = mapper.readValue(response.body(), Map.class);
                if ("COMPLETE".equals(status.get("phase"))) {
                    return status;
                }
                assertThat(status.get("phase")).as("%s%n%s", response.body(), indexer.getLogs()).isNotEqualTo("FAILED");
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("job did not complete: " + lookup + System.lineSeparator() + indexer.getLogs());
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

    private HttpResponse<String> post(String base, String path, String token, Map<String, Object> body) throws Exception {
        return postUri(base + path, token, JsonMapper.builder().build().writeValueAsString(body));
    }

    private HttpResponse<String> postUri(String uri, String token, String body) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30)).header("X-Api-Token", token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String base, String path, String token) throws IOException, InterruptedException {
        return getUri(base + path, token);
    }

    private HttpResponse<String> getUri(String uri, String token) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30)).header("X-Api-Token", token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }


    private static Map<?, ?> acceptedReview(HttpResponse<String> response, JsonMapper mapper) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        Map<?, ?> body = mapper.readValue(response.body(), Map.class);
        assertReviewComparisonType(body);
        return body;
    }

    private static void assertReviewComparisonType(Map<?, ?> response) {
        Map<?, ?> review = map(response, "review");
        assertThat(text(map(review, "selection"), "kind")).isEqualTo("COMMIT");
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
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            return process.exitValue();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(10, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("forced Query/bootstrap shutdown").isTrue();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
