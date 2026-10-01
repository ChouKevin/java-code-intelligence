package com.java.semantic.indexer.job;

import com.java.semantic.model.index.IndexCollections;
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
import org.eclipse.jgit.lib.ObjectId;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the production Git-review path with separately packaged Indexer and Query applications. */
@Tag("mongo-it")
class GitReviewContextJourneyIT {
    private static final String REPOSITORY_ID = "review-fixture";
    private static final String ADMIN_TOKEN = "journey-admin-token";
    private static final String OTHER_REPOSITORY_ID = "other-review-fixture";
    private static final String QUERY_TOKEN = "journey-query-token";
    private static final String TOKEN_HEADER = "X-Api-Token";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);
    // Two full review endpoints, including multi-MiB paging fixtures, need the real analysis deadline.
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(10);

    private HttpClient http;

    @TempDir
    Path temporaryDirectory;

    @Test
    void follows_the_real_admin_dispatcher_mongo_http_and_mcp_git_review_journey() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("git.review.journey.enabled"),
                "the external process journey is opt-in and requires fresh executable jars");
        Path indexerJar = requiredJar("git.review.journey.indexer.jar");
        Path queryJar = requiredJar("git.review.journey.query.jar");
        Path remotePath = temporaryDirectory.resolve("review-remote.git");
        Path seedPath = temporaryDirectory.resolve("review-seed");
        String jdtHomeValue = System.getenv("JDTLS_HOME");
        assertThat(jdtHomeValue).as("opt-in journey requires explicit JDTLS_HOME").isNotBlank();
        Path jdtHome = Path.of(jdtHomeValue);
        assertThat(Files.isDirectory(jdtHome)).as("real JDT LS installation").isTrue();
        Path jdtWorkspace = temporaryDirectory.resolve("jdt-workspace");
        int indexerPort = availablePort();
        int queryPort = availablePort();
        try (HttpClient journeyHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
             MongoDBContainer mongo = new MongoDBContainer("mongo:8.0.4");
             Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            http = journeyHttp;
            mongo.start();
            String mongoUri = mongo.getConnectionString() + "/git_review_journey";
            bootstrapSchema(indexerJar, mongoUri, jdtHome);
            String previous = seedFixture(seed, seedPath, remotePath);
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            String current = updateFixture(seed, seedPath);
            Topology topology = extendTopology(seed, seedPath, previous, current);
            RunningProcess indexer = startIndexer(indexerJar, mongoUri, remotePath, indexerPort, jdtHome, jdtWorkspace);
            try {
                String indexerBase = "http://127.0.0.1:" + indexerPort;
                awaitHttp(indexerBase + "/index/repositories/" + REPOSITORY_ID + "/metadata", QUERY_TOKEN, indexer);
                assertThat(post(indexerBase, "/index/repositories/" + REPOSITORY_ID + "/metadata", QUERY_TOKEN,
                        Map.of("requestId", savedRequestId("unauthorized"))).statusCode())
                        .isEqualTo(401);

                JsonMapper mapper = JsonMapper.builder().build();
                String metadataRequestId = savedRequestId("metadata");
                String reviewRequestId = savedRequestId("range");
                Map<?, ?> catalogStatus;
                Map<?, ?> comparisonStatus;
                List<ReviewCase> cases = new ArrayList<>();
                try (McpSyncClient preparation = nativeClient(indexerBase, ADMIN_TOKEN, mapper)) {
                    preparation.initialize();
                    assertThat(preparation.listTools().tools()).extracting(McpSchema.Tool::name)
                            .contains("refresh_repository_metadata", "prepare_review", "get_job");
                    discardSubmission(preparation, "refresh_repository_metadata",
                            Map.of("repositoryId", REPOSITORY_ID, "requestId", metadataRequestId));
                    catalogStatus = completedRequest(indexerBase, metadataRequestId, mapper, indexer);
                    assertRecoveredIdentity(preparation, indexerBase, metadataRequestId, catalogStatus, mapper);
                    discardSubmission(preparation, "prepare_review", Map.of("repositoryId", REPOSITORY_ID,
                            "requestId", reviewRequestId, "selection", range(previous, current)));
                    comparisonStatus = completedRequest(indexerBase, reviewRequestId, mapper, indexer);
                    assertRecoveredIdentity(preparation, indexerBase, reviewRequestId, comparisonStatus, mapper);
                    assertPreparationErrors(preparation, indexerBase, mongoUri, metadataRequestId, reviewRequestId,
                            text(catalogStatus, "jobId"), previous, current, mapper);
                    // The same durable requestId is legal in a different repository, not a global identity.
                    discardSubmission(preparation, "refresh_repository_metadata",
                            Map.of("repositoryId", OTHER_REPOSITORY_ID, "requestId", metadataRequestId));
                    Map<?, ?> otherMetadata = completedRequest(indexerBase, OTHER_REPOSITORY_ID, metadataRequestId, mapper, indexer);
                    assertThat(text(otherMetadata, "jobId")).isNotEqualTo(text(catalogStatus, "jobId"));
                    assertThat(mcpBody(preparation, "get_job",
                            Map.of("repositoryId", OTHER_REPOSITORY_ID, "requestId", metadataRequestId), mapper))
                            .isEqualTo(otherMetadata);
                    assertRecoveredIdentity(preparation, indexerBase, metadataRequestId, catalogStatus, mapper);
                    cases.add(prepareCase(preparation, indexerBase, "ordinary", commit(current), previous, current,
                            "FIRST_PARENT", "previous", "current", mapper, indexer));
                    cases.add(prepareCase(preparation, indexerBase, "root", commit(previous), "", previous,
                            "EMPTY_TREE", "", "previous", mapper, indexer));
                    cases.add(prepareCase(preparation, indexerBase, "merge", commit(topology.merge()), current, topology.merge(),
                            "FIRST_PARENT", "current", "current", mapper, indexer));
                    cases.add(prepareCase(preparation, indexerBase, "equal", range(current, current), current, current,
                            "DIRECT_RANGE", "current", "current", mapper, indexer));
                    cases.add(prepareCase(preparation, indexerBase, "reversed", range(current, previous), current, previous,
                            "DIRECT_RANGE", "current", "previous", mapper, indexer));
                    cases.add(prepareCase(preparation, indexerBase, "divergent", range(current, topology.side()), current, topology.side(),
                            "DIRECT_RANGE", "current", "previous", mapper, indexer));
                    cases.add(prepareCase(preparation, indexerBase, "excluded", range(current, topology.policy()), current, topology.policy(),
                            "DIRECT_RANGE", "current", "current", mapper, indexer));
                    assertRecoveredIdentity(preparation, indexerBase, metadataRequestId, catalogStatus, mapper);
                    assertRecoveredIdentity(preparation, indexerBase, reviewRequestId, comparisonStatus, mapper);
                }
                String catalogId = text(map(catalogStatus, "metadataResult"), "catalogId");
                String historyId = text(map(catalogStatus, "metadataResult"), "historyId");
                assertThat(text(map(catalogStatus, "metadataResult"), "headRevision")).isEqualTo(current);
                Map<?, ?> comparisonEvidence = map(comparisonStatus, "review");
                String reviewId = text(comparisonEvidence, "reviewId");
                assertThat(text(map(comparisonEvidence, "resolvedEndpoints"), "afterRevision")).isEqualTo(current);

                // Construct the publication-before-terminal crash window only after the real worker exits.
                indexer.close();
                Document originalComparison = restorePublishedRunningJobs(mongoUri, comparisonStatus);
                indexer = startIndexer(indexerJar, mongoUri, remotePath, indexerPort, jdtHome, jdtWorkspace);
                awaitHttp(indexerBase + "/index/repositories/" + REPOSITORY_ID + "/metadata", ADMIN_TOKEN, indexer);
                try (McpSyncClient preparation = nativeClient(indexerBase, ADMIN_TOKEN, mapper)) {
                    preparation.initialize();
                    Map<?, ?> recoveredMetadata = completedRequest(indexerBase, metadataRequestId, mapper, indexer);
                    Map<?, ?> recoveredReview = completedRequest(indexerBase, reviewRequestId, mapper, indexer);
                    assertThat(recoveredMetadata).isEqualTo(catalogStatus);
                    assertThat(recoveredReview).isEqualTo(comparisonStatus);
                    assertThat(readyComparison(mongoUri, text(map(comparisonStatus, "review"), "comparisonId")))
                            .isEqualTo(originalComparison);
                    System.out.println("Constructed publication-before-terminal recovery: original READY comparison validated in a new Indexer JVM");
                    assertRecoveredIdentity(preparation, indexerBase, metadataRequestId, catalogStatus, mapper);
                    assertRecoveredIdentity(preparation, indexerBase, reviewRequestId, comparisonStatus, mapper);
                }

                write(seedPath, "src/Movement.java", "class Movement { }\n");
                seed.add().addFilepattern("src/Movement.java").call();
                seed.commit().setMessage("remote moved after evidence preparation").setAuthor("Fixture", "fixture@example.test")
                        .setCommitter("Fixture", "fixture@example.test").call();
                push(seed, "main");

                indexer.close();
                assertThat(Files.isDirectory(jdtHome)).isTrue();
                assertThat(Files.isDirectory(jdtWorkspace)).isTrue();
                Files.move(remotePath, temporaryDirectory.resolve("remote-unavailable"));
                Files.move(seedPath, temporaryDirectory.resolve("seed-unavailable"));
                Files.move(temporaryDirectory.resolve("checkouts"), temporaryDirectory.resolve("checkouts-unavailable"));

                assertDefaultPolicyDenies(queryJar, mongoUri, queryPort, mapper);
                RunningProcess query = startQuery(queryJar, mongoUri, queryPort, true, false);
                try {
                    String queryBase = "http://127.0.0.1:" + queryPort;
                    awaitHttp(queryBase + "/api/v1/git/branches", QUERY_TOKEN, query);
                    Map<?, ?> branches = successful(post(queryBase, "/api/v1/git/branches", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "limit", 1)), mapper);
                    assertThat(text(map(branches, "metadata"), "catalogId")).isEqualTo(catalogId);
                    assertThat(map(branches, "page").get("hasMore")).isEqualTo(Boolean.TRUE);
                    List<Map<?, ?>> branchItems = exhaustBranches(queryBase, catalogId, mapper);
                    assertThat(branchItems).extracting(item -> text(item, "headRevision")).contains(current, previous);
                    Map<?, ?> commits = successful(post(queryBase, "/api/v1/git/commits", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "branch", "main", "limit", 1)), mapper);
                    assertThat(text(map(commits, "metadata"), "historyId")).isEqualTo(historyId);
                    List<Map<?, ?>> history = exhaustHistory(queryBase, historyId, mapper);
                    assertThat(history).extracting(item -> text(item, "revision")).contains(current, previous);
                    Map<?, ?> discovery = successful(post(queryBase, "/api/v1/context", QUERY_TOKEN,
                            Map.of("repositoryId", REPOSITORY_ID, "selector", Map.of("kind", "REVIEW", "reviewId", reviewId))), mapper);
                    Map<?, ?> beforeContext = map(map(discovery, "before"), "context");
                    Map<?, ?> afterContext = map(map(discovery, "after"), "context");
                    Map<?, ?> comparisonContext = map(discovery, "comparisonContext");
                    Map<?, ?> comparison = successful(post(queryBase, "/api/v1/git/comparisons", QUERY_TOKEN,
                            Map.of("comparisonContext", comparisonContext, "limit", 100)), mapper);
                    Map<?, ?> deleted = matching(mapList(comparison, "items"), "kind", "DELETE");
                    assertThat(text(map(deleted, "before"), "path")).isEqualTo("src/DeletedPrevious.java");
                    String largeDiff = exhaustDiff(queryBase, comparisonContext,
                            text(matchingPath(mapList(comparison, "items"), "src/LargeDiff.java"), "changeId"), mapper);
                    assertThat(largeDiff).contains("changed-diff previous 0", "changed-diff current 6999");
                    Map<?, ?> files = successful(post(queryBase, "/api/v1/files", QUERY_TOKEN,
                            Map.of("context", afterContext, "directory", "src", "limit", 1)), mapper);
                    assertThat(map(files, "page").get("hasMore")).isEqualTo(Boolean.TRUE);
                    assertThat(exhaustFile(queryBase, afterContext, mapper)).isEqualTo(serviceSource("current"));
                    List<Map<?, ?>> stableMatches = exhaustSearch(queryBase, afterContext, "stable-token", mapper).items();
                    assertThat(stableMatches).hasSize(30);
                    assertThat(stableMatches).allSatisfy(match -> assertThat(text(match, "snippet")).contains("stable-token current"));
                    SearchTraversal noHitSearch = exhaustSearch(queryBase, afterContext, "definitely-absent", mapper);
                    assertThat(noHitSearch.items()).isEmpty();
                    assertThat(noHitSearch.pages()).isGreaterThan(1);
                    assertUnchangedAndDeletedFiles(queryBase, beforeContext, afterContext, mapper);
                    assertThat(post(queryBase, "/api/v1/git/branches", "wrong-token", Map.of("repositoryId", REPOSITORY_ID)).statusCode()).isEqualTo(401);
                    assertPendingEvidence(queryBase, mongoUri, mapper);
                    assertMcpJourney(queryBase, catalogId, historyId, comparisonContext, beforeContext, afterContext, mapper);
                    assertGitMatrix(queryBase, cases, mapper);
                    assertThat(exhaustBranches(queryBase, catalogId, mapper)).isEqualTo(branchItems);
                    assertThat(exhaustHistory(queryBase, historyId, mapper)).isEqualTo(history);
                } finally {
                    query.close();
                }
                assertLaterPolicyDenies(queryJar, mongoUri, queryPort, mapper);
                assertGranularPolicyDenies(queryJar, mongoUri, queryPort, reviewId, previous, current, mapper);
            } finally {
                indexer.close();
            }
        }
    }

    private String savedRequestId(String label) throws IOException {
        String requestId = UUID.randomUUID().toString();
        Files.writeString(temporaryDirectory.resolve(label + "-request-id"), requestId);
        return requestId;
    }

    private static Map<String, String> commit(String revision) {
        return Map.of("kind", "COMMIT", "revision", revision);
    }

    private static Map<String, String> range(String before, String after) {
        return Map.of("kind", "RANGE", "beforeRevision", before, "afterRevision", after);
    }

    private static McpSyncClient nativeClient(String base, String token, JsonMapper mapper) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header(TOKEN_HEADER, token)).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30)).build();
    }

    private static void discardSubmission(McpSyncClient client, String operation, Map<String, Object> arguments) {
        // Deliberately ignore both payloads: recovery cannot use the acceptance jobId.
        assertThat(client.callTool(McpSchema.CallToolRequest.builder(operation).arguments(arguments).build()).isError()).isFalse();
    }

    private void assertRecoveredIdentity(McpSyncClient client, String base, String requestId,
            Map<?, ?> original, JsonMapper mapper) throws Exception {
        Map<?, ?> recovered = mcpBody(client, "get_job", Map.of("repositoryId", REPOSITORY_ID, "requestId", requestId), mapper);
        assertThat(recovered).isEqualTo(original);
        assertThat(text(recovered, "requestId")).isEqualTo(requestId);
        assertThat(successful(get(base, "/index/repositories/" + REPOSITORY_ID + "/jobs?jobId="
                + text(original, "jobId"), ADMIN_TOKEN), mapper)).isEqualTo(original);
    }

    private ReviewCase prepareCase(McpSyncClient client, String base, String name, Map<String, String> selection,
            String before, String after, String baseline, String beforeSource, String afterSource,
            JsonMapper mapper, RunningProcess indexer) throws Exception {
        String requestId = savedRequestId(name);
        discardSubmission(client, "prepare_review", Map.of("repositoryId", REPOSITORY_ID,
                "requestId", requestId, "selection", selection));
        Map<?, ?> status = completedRequest(base, requestId, mapper, indexer);
        assertRecoveredIdentity(client, base, requestId, status, mapper);
        Map<?, ?> review = map(status, "review");
        Map<?, ?> resolved = map(review, "resolvedEndpoints");
        assertThat(text(resolved, "baselineRule")).isEqualTo(baseline);
        assertThat(text(resolved, "afterRevision")).isEqualTo(after);
        assertThat(resolved.get("beforeRevision")).isEqualTo(before.isEmpty() ? null : before);
        assertThat(map(map(status, "requested"), "selection")).isEqualTo(selection);
        return new ReviewCase(name, text(review, "reviewId"), selection, before, after, beforeSource, afterSource);
    }

    private void assertPreparationErrors(McpSyncClient client, String base, String mongoUri,
            String metadataRequestId, String reviewRequestId, String jobId, String previous, String current,
            JsonMapper mapper) throws Exception {
        long jobs;
        try (MongoClient mongo = MongoClients.create(mongoUri)) {
            jobs = mongo.getDatabase("git_review_journey").getCollection(IndexCollections.INDEX_JOBS).countDocuments();
        }
        preparationError(client, base, "refresh_repository_metadata", "/metadata",
                Map.of("repositoryId", REPOSITORY_ID, "requestId", metadataRequestId), 409, "REQUEST_ID_REUSED", mapper);
        preparationError(client, base, "prepare_review", "/reviews",
                Map.of("repositoryId", REPOSITORY_ID, "requestId", metadataRequestId, "selection", range(previous, current)),
                409, "REQUEST_ID_REUSED", mapper);
        preparationError(client, base, "prepare_review", "/reviews",
                Map.of("repositoryId", REPOSITORY_ID, "requestId", reviewRequestId, "selection", commit(current)),
                409, "REQUEST_ID_REUSED", mapper);
        for (Map<String, Object> selector : List.<Map<String, Object>>of(
                Map.of("repositoryId", REPOSITORY_ID),
                Map.of("repositoryId", REPOSITORY_ID, "requestId", metadataRequestId, "jobId", jobId),
                Map.of("repositoryId", REPOSITORY_ID, "requestId", "not-a-canonical-uuid"))) {
            preparationError(client, base, "get_job", "/jobs", selector, 400, "INVALID_ARGUMENT", mapper);
        }
        preparationError(client, base, "get_job", "/jobs",
                Map.of("repositoryId", OTHER_REPOSITORY_ID, "requestId", metadataRequestId),
                404, "REQUEST_NOT_FOUND", mapper);
        preparationError(client, base, "get_job", "/jobs",
                Map.of("repositoryId", OTHER_REPOSITORY_ID, "jobId", jobId), 404, "JOB_NOT_FOUND", mapper);
        String unknown = savedRequestId("unknown-never-submitted");
        preparationError(client, base, "get_job", "/jobs",
                Map.of("repositoryId", REPOSITORY_ID, "requestId", unknown), 404, "REQUEST_NOT_FOUND", mapper);
        preparationError(client, base, "prepare_review", "/reviews",
                Map.of("repositoryId", REPOSITORY_ID, "requestId", savedRequestId("malformed-selection"),
                        "selection", Map.of("kind", "COMMIT", "revision", current, "beforeRevision", previous)),
                400, "INVALID_ARGUMENT", mapper);
        preparationError(client, base, "refresh_repository_metadata", "/metadata",
                Map.of("repositoryId", REPOSITORY_ID, "requestId", "INVALID"), 400, "INVALID_ARGUMENT", mapper);
        try (MongoClient mongo = MongoClients.create(mongoUri)) {
            assertThat(mongo.getDatabase("git_review_journey").getCollection(IndexCollections.INDEX_JOBS).countDocuments())
                    .as("duplicate submissions and unknown lookups accept no second job").isEqualTo(jobs);
        }
    }

    private void preparationError(McpSyncClient client, String base, String tool, String suffix,
            Map<String, Object> arguments, int status, String code, JsonMapper mapper) throws Exception {
        String repository = (String) arguments.get("repositoryId");
        String path = "/index/repositories/" + repository + suffix;
        HttpResponse<String> response;
        if (tool.equals("get_job")) {
            List<String> selectors = new ArrayList<>();
            for (String key : List.of("requestId", "jobId")) {
                if (arguments.containsKey(key)) selectors.add(key + "=" + arguments.get(key));
            }
            response = get(base, path + (selectors.isEmpty() ? "" : "?" + String.join("&", selectors)), ADMIN_TOKEN);
        } else {
            Map<String, Object> body = new java.util.HashMap<>(arguments);
            body.remove("repositoryId");
            response = post(base, path, ADMIN_TOKEN, body);
        }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        Map<?, ?> error = mcpResult(client, tool, arguments, mapper, true);
        assertThat(text(error, "code")).isEqualTo(code);
        assertThat(mapper.readTree(response.body())).isEqualTo(mapper.valueToTree(error));
        if (repository.equals(OTHER_REPOSITORY_ID)) {
            assertThat(error.containsKey("jobId")).isFalse();
            assertThat(error.containsKey("requestId")).isFalse();
        }
    }

    private static Document restorePublishedRunningJobs(String mongoUri, Map<?, ?> review) {
        try (MongoClient client = MongoClients.create(mongoUri)) {
            MongoDatabase database = client.getDatabase("git_review_journey");
            Document reviewJob = database.getCollection(IndexCollections.INDEX_JOBS)
                    .find(new Document("repoId", REPOSITORY_ID).append("jobId", text(review, "jobId"))).first();
            assertThat(reviewJob).isNotNull();
            String comparisonId = reviewJob.get("review", Document.class).getString("comparisonId");
            Document ready = database.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new Document("repoId", REPOSITORY_ID).append("evidenceId", comparisonId)
                            .append("kind", "COMPARISON").append("state", "READY")).first();
            assertThat(ready).isNotNull();
            assertThat(ready.get("policyCoverage", Document.class).getLong("excludedChanges")).isEqualTo(3L);
            assertThat(ready.getString("contentDigest")).hasSize(64);
            // Immutable graph stays untouched. Startup can complete VALIDATING only through readiness validation.
            assertThat(database.getCollection(IndexCollections.INDEX_JOBS).updateOne(
                    new Document("jobId", text(review, "jobId")).append("phase", "COMPLETE").append("active", false),
                    new Document("$set", new Document("phase", "RUNNING").append("active", true)
                            .append("review.stage", "VALIDATING"))).getModifiedCount()).isEqualTo(1);
            return ready;
        }
    }

    private static Document readyComparison(String mongoUri, String comparisonId) {
        try (MongoClient client = MongoClients.create(mongoUri)) {
            return client.getDatabase("git_review_journey").getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new Document("repoId", REPOSITORY_ID).append("evidenceId", comparisonId)
                            .append("kind", "COMPARISON").append("state", "READY")).first();
        }
    }

    private static Topology extendTopology(Git seed, Path root, String previous, String current) throws Exception {
        seed.checkout().setCreateBranch(true).setName("side").setStartPoint(previous).call();
        write(root, "src/Side.java", "class Side { int evidence() { return 17; } }\n");
        seed.add().addFilepattern("src/Side.java").call();
        String side = fixtureCommit(seed, "divergent side");
        push(seed, "side");
        seed.checkout().setCreateBranch(true).setName("merge").setStartPoint(current).call();
        assertThat(seed.merge().include(ObjectId.fromString(side)).call().getMergeStatus().isSuccessful()).isTrue();
        String merge = seed.getRepository().resolve("HEAD").name();
        push(seed, "merge");
        seed.checkout().setCreateBranch(true).setName("policy").setStartPoint(current).call();
        write(root, "docs/excluded-secret.md", "excluded-only-secret\n");
        seed.add().addFilepattern("docs/excluded-secret.md").call();
        String policy = fixtureCommit(seed, "excluded-only change");
        push(seed, "policy");
        seed.checkout().setName("main").call();
        return new Topology(side, merge, policy);
    }

    private static String fixtureCommit(Git seed, String message) throws Exception {
        return seed.commit().setMessage(message).setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").call().getId().name();
    }

    private static Path requiredJar(String property) {
        String value = System.getProperty(property, "");
        Path jar = Path.of(value).toAbsolutePath();
        assertThat(Files.isRegularFile(jar)).as("fresh executable jar supplied by the opt-in script: %s", property).isTrue();
        return jar;
    }

    private void bootstrapSchema(Path indexerJar, String mongoUri, Path jdtHome) throws Exception {
        RunningProcess bootstrap = start(indexerJar, List.of("--semantic.schema-bootstrap=true", "--spring.mongodb.uri=" + mongoUri,
                "--semantic.jdtls.home=" + jdtHome, "--spring.main.banner-mode=off"));
        try {
            assertThat(bootstrap.await(Duration.ofSeconds(30))).isZero();
        } finally {
            bootstrap.close();
        }
    }

    private String seedFixture(Git seed, Path seedPath, Path remotePath) throws Exception {
        seed.getRepository().getConfig().setString("user", null, "name", "Fixture");
        seed.getRepository().getConfig().setString("user", null, "email", "fixture@example.test");
        seed.getRepository().getConfig().save();
        write(seedPath, "pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId><artifactId>git-review</artifactId><version>1</version>
                  <build><sourceDirectory>src</sourceDirectory></build>
                  <properties><maven.compiler.release>21</maven.compiler.release></properties>
                </project>
                """);
        write(seedPath, "src/Caller.java", "class Caller { int call() { return Service.version(); } }\n");
        write(seedPath, "src/Service.java", serviceSource("previous"));
        write(seedPath, "src/LargeDiff.java", largeDiffSource("previous"));
        write(seedPath, "src/HugeNoHit.java", hugeNoHitSource());
        writeNoHitFiles(seedPath);
        write(seedPath, "src/DeletedPrevious.java", "class DeletedPrevious { }\n");
        write(seedPath, "src/RenameBefore.java", "class RenamedEvidence { }\n");
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
        write(seedPath, "config/review.properties", "review.mode=changed-excluded\n");
        write(seedPath, "docs/review.md", "# Changed excluded documentation\n");
        Files.write(seedPath.resolve("assets/binary.dat"), new byte[] {0, 1, 2, 3});
        seed.add().addFilepattern("config/review.properties").call();
        seed.add().addFilepattern("docs/review.md").call();
        seed.add().addFilepattern("assets/binary.dat").call();
        write(seedPath, "src/Service.java", serviceSource("current"));
        write(seedPath, "src/LargeDiff.java", largeDiffSource("current"));
        Files.delete(seedPath.resolve("src/DeletedPrevious.java"));
        Files.move(seedPath.resolve("src/RenameBefore.java"), seedPath.resolve("src/RenameAfter.java"));
        seed.rm().addFilepattern("src/RenameBefore.java").call();
        seed.add().addFilepattern("src/RenameAfter.java").call();
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
        // One regular Java comment exceeds a 64-KiB source page without exceeding the file policy.
        source.append("  // ").append("來源證據".repeat(10_000)).append("\n");
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

    private RunningProcess startIndexer(Path jar, String mongoUri, Path remotePath, int port, Path jdtHome, Path jdtWorkspace)
            throws IOException {
        Path checkoutRoot = Files.createDirectories(temporaryDirectory.resolve("checkouts"));
        // Controlled repository-owned fixture on the host; production LINUX_UID is exercised by the image journey.
        return start(jar, List.of("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1", "--server.port=" + port,
                "--semantic.indexer.admin-token=" + ADMIN_TOKEN, "--semantic.repositories." + REPOSITORY_ID + ".url=" + remotePath.toUri(),
                "--semantic.repositories." + REPOSITORY_ID + ".default-branch=main", "--semantic.data-root=" + checkoutRoot,
                "--semantic.repositories." + OTHER_REPOSITORY_ID + ".url=" + remotePath.toUri(),
                "--semantic.repositories." + OTHER_REPOSITORY_ID + ".default-branch=main",
                "--semantic.jdtls.home=" + jdtHome, "--semantic.jdtls.workspace-data-root=" + jdtWorkspace,
                "--semantic.jdtls.isolation-mode=LOCAL_TRUSTED",
                "--semantic.jdtls.java-executable=" + Path.of(System.getProperty("java.home"), "bin", "java"),
                // Keep the >4-MiB scan-budget fixture inside the configured per-file export ceiling.
                "--semantic.git-evidence-file-text-bytes=8388608",
                "--semantic.index-jobs.poll-delay=20ms", "--spring.main.banner-mode=off"));
    }

    private RunningProcess startQuery(Path jar, String mongoUri, int port, boolean allowed, boolean forbidden) throws IOException {
        return startQuery(jar, mongoUri, port, allowed, forbidden, List.of());
    }

    private RunningProcess startQuery(Path jar, String mongoUri, int port, boolean allowed, boolean forbidden,
            List<String> policyArguments) throws IOException {
        List<String> arguments = new ArrayList<>(List.of("--spring.mongodb.uri=" + mongoUri, "--server.address=127.0.0.1", "--server.port=" + port,
                "--semantic.query.api-token=" + QUERY_TOKEN, "--spring.main.banner-mode=off"));
        if (allowed) {
            arguments.add("--semantic.query.git-evidence.allowed-repositories[0]=" + REPOSITORY_ID);
        }
        if (forbidden) {
            arguments.add("--semantic.query.read-policy.forbidden-repositories[0]=" + REPOSITORY_ID);
        }
        arguments.addAll(policyArguments);
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

    private void assertGranularPolicyDenies(Path queryJar, String mongoUri, int port, String reviewId,
            String before, String after, JsonMapper mapper) throws Exception {
        RunningProcess query = startQuery(queryJar, mongoUri, port, true, false, List.of(
                "--semantic.query.read-policy.forbidden-packages[0].repo-id=" + REPOSITORY_ID,
                "--semantic.query.read-policy.forbidden-packages[0].package-prefix=restricted"));
        try {
            String base = "http://127.0.0.1:" + port;
            awaitHttp(base + "/api/v1/git/branches", QUERY_TOKEN, query);
            try (McpSyncClient client = nativeClient(base, QUERY_TOKEN, mapper)) {
                client.initialize();
                Map<String, Object> comparison = Map.of("repositoryId", REPOSITORY_ID, "reviewId", reviewId,
                        "before", Map.of("kind", "REVISION", "revision", before),
                        "after", Map.of("kind", "REVISION", "revision", after));
                queryError(client, base, "list_git_branches", "/api/v1/git/branches",
                        Map.of("repositoryId", REPOSITORY_ID), 404, "REPOSITORY_NOT_FOUND", mapper);
                queryError(client, base, "list_git_commits", "/api/v1/git/commits",
                        Map.of("repositoryId", REPOSITORY_ID, "branch", "main"), 404, "REPOSITORY_NOT_FOUND", mapper);
                queryError(client, base, "compare_revisions", "/api/v1/git/comparisons",
                        Map.of("comparisonContext", comparison), 404, "REPOSITORY_NOT_FOUND", mapper);
                queryError(client, base, "get_file_diff", "/api/v1/git/file-diff",
                        Map.of("comparisonContext", comparison, "changeId", "not-disclosed"),
                        404, "REPOSITORY_NOT_FOUND", mapper);
            }
        } finally {
            query.close();
        }
    }

    private void assertPendingEvidence(String queryBase, String mongoUri, JsonMapper mapper) throws Exception {
        String pendingId = "pending-review";
        try (MongoClient client = MongoClients.create(mongoUri)) {
            client.getDatabase("git_review_journey").getCollection(IndexCollections.REVIEW_MANIFESTS)
                    .insertOne(new org.bson.Document("repoId", REPOSITORY_ID).append("reviewId", pendingId).append("state", "PREPARING"));
        }
        HttpResponse<String> response = post(queryBase, "/api/v1/source", QUERY_TOKEN,
                Map.of("context", Map.of("kind", "REVIEW", "repositoryId", REPOSITORY_ID, "reviewId", pendingId, "side", "AFTER",
                        "revision", "a".repeat(40)), "target", Map.of("kind", "FILE", "path", "src/Service.java")));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(response.body()).get("code").asString()).isEqualTo("REVIEW_NOT_READY");
    }

    private void assertMcpJourney(String queryBase, String catalogId, String historyId, Map<?, ?> comparisonContext,
            Map<?, ?> beforeContext, Map<?, ?> afterContext, JsonMapper mapper) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(queryBase + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header(TOKEN_HEADER, QUERY_TOKEN)).build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(30)).build()) {
            client.initialize();
            Map<?, ?> branches = mcpBody(client, "list_git_branches", Map.of("repositoryId", REPOSITORY_ID), mapper);
            assertThat(text(map(branches, "metadata"), "catalogId")).isEqualTo(catalogId);
            Map<?, ?> commits = mcpBody(client, "list_git_commits", Map.of("repositoryId", REPOSITORY_ID, "branch", "main"), mapper);
            assertThat(text(map(commits, "metadata"), "historyId")).isEqualTo(historyId);
            Map<?, ?> comparison = mcpBody(client, "compare_revisions", Map.of("comparisonContext", comparisonContext), mapper);
            Map<?, ?> modified = matchingPath(mapList(comparison, "items"), "src/LargeDiff.java");
            Map<?, ?> patch = mcpBody(client, "get_file_diff", Map.of("comparisonContext", comparisonContext, "changeId", text(modified, "changeId")), mapper);
            assertThat(text(patch, "patch")).contains("changed-diff previous");
            Map<?, ?> file = mcpBody(client, "read_source", Map.of("context", beforeContext,
                    "target", Map.of("kind", "FILE", "path", "src/Service.java", "startLine", 3), "maxLines", 1), mapper);
            assertThat(text(file, "content")).contains("stable-token previous");
            Map<?, ?> deleted = matching(mapList(comparison, "items"), "kind", "DELETE");
            Map<?, ?> terminalDiff = mcpBody(client, "get_file_diff", Map.of("comparisonContext", comparisonContext, "changeId", text(deleted, "changeId")), mapper);
            assertThat(terminalDiff.get("complete")).isEqualTo(Boolean.TRUE);
            assertNoCursor(terminalDiff);
            Map<?, ?> terminalFile = mcpBody(client, "read_source", Map.of("context", beforeContext,
                    "target", Map.of("kind", "FILE", "path", "src/DeletedPrevious.java")), mapper);
            assertThat(text(terminalFile, "content")).contains("class DeletedPrevious");
            assertNoCursor(terminalFile);
            McpSearchTraversal terminalSearch = exhaustMcpSearch(client, afterContext, "stable-token", mapper);
            assertThat(terminalSearch.items()).hasSize(30);
            assertThat(terminalSearch.items()).allSatisfy(match -> assertThat(text(match, "snippet")).contains("stable-token current"));
            assertThat(terminalSearch.finalPage().get("scanComplete")).isEqualTo(Boolean.TRUE);
            assertNoCursor(map(terminalSearch.finalPage(), "page"));
        }
    }

    private void assertGitMatrix(String base, List<ReviewCase> cases, JsonMapper mapper) throws Exception {
        try (McpSyncClient client = nativeClient(base, QUERY_TOKEN, mapper)) {
            client.initialize();
            assertMetadataParity(client, base, "list_git_branches", "/api/v1/git/branches",
                    Map.of("repositoryId", REPOSITORY_ID), "catalogId", mapper);
            assertMetadataParity(client, base, "list_git_commits", "/api/v1/git/commits",
                    Map.of("repositoryId", REPOSITORY_ID, "branch", "main"), "historyId", mapper);
            queryError(client, base, "list_git_branches", "/api/v1/git/branches",
                    Map.of("repositoryId", REPOSITORY_ID, "limit", 0), 400, "INVALID_ARGUMENT", mapper);
            queryError(client, base, "list_git_branches", "/api/v1/git/branches",
                    Map.of("repositoryId", OTHER_REPOSITORY_ID), 404, "REPOSITORY_NOT_FOUND", mapper);
            queryError(client, base, "list_git_commits", "/api/v1/git/commits",
                    Map.of("repositoryId", REPOSITORY_ID), 400, "INVALID_ARGUMENT", mapper);
            queryError(client, base, "list_git_commits", "/api/v1/git/commits",
                    Map.of("repositoryId", REPOSITORY_ID, "branch", "unprepared-branch"),
                    409, "METADATA_NOT_PREPARED", mapper);
            for (ReviewCase scenario : cases) {
                Map<String, Object> discoveryRequest = Map.of("repositoryId", REPOSITORY_ID,
                        "selector", Map.of("kind", "REVIEW", "reviewId", scenario.reviewId()));
                Map<?, ?> discovery = queryParity(client, base, "get_context", "/api/v1/context", discoveryRequest, mapper);
                assertThat(text(discovery, "state")).isEqualTo("READY");
                Map<?, ?> bySelection = queryParity(client, base, "get_context", "/api/v1/context",
                        Map.of("repositoryId", REPOSITORY_ID, "selector", scenario.selection()), mapper);
                assertThat(text(bySelection, "reviewId")).isEqualTo(scenario.reviewId());
                Map<?, ?> context = map(discovery, "comparisonContext");
                assertThat(text(map(context, "after"), "revision")).isEqualTo(scenario.after());
                Map<?, ?> after = map(map(discovery, "after"), "context");
                assertThat(text(after, "revision")).isEqualTo(scenario.after());
                assertSourceParity(client, base, after, scenario.afterSource(), mapper);
                if (scenario.before().isEmpty()) {
                    assertThat(text(map(discovery, "before"), "kind")).isEqualTo("EMPTY_TREE");
                    assertThat(map(discovery, "before").containsKey("context")).isFalse();
                    assertThat(map(context, "before").containsKey("revision")).isFalse();
                    queryError(client, base, "read_source", "/api/v1/source",
                            Map.of("context", Map.of("kind", "REVIEW", "repositoryId", REPOSITORY_ID,
                                    "reviewId", scenario.reviewId(), "side", "BEFORE", "revision", scenario.after()),
                                    "target", Map.of("kind", "FILE", "path", "src/Service.java")),
                            409, "REVIEW_CONTEXT_MISMATCH", mapper);
                } else {
                    assertThat(text(map(context, "before"), "revision")).isEqualTo(scenario.before());
                    Map<?, ?> before = map(map(discovery, "before"), "context");
                    assertThat(text(before, "revision")).isEqualTo(scenario.before());
                    assertSourceParity(client, base, before, scenario.beforeSource(), mapper);
                }
                queryParity(client, base, "compare_revisions", "/api/v1/git/comparisons",
                        Map.of("comparisonContext", context), mapper);
                Map<?, ?> comparison = queryParity(client, base, "compare_revisions", "/api/v1/git/comparisons",
                        Map.of("comparisonContext", context, "limit", 100), mapper);
                List<Map<?, ?>> changes = mapList(comparison, "items");
                Map<?, ?> coverage = map(comparison, "policyCoverage");
                if (scenario.name().equals("equal") || scenario.name().equals("excluded")) {
                    assertThat(changes).isEmpty();
                    assertThat(((Number) coverage.get("excludedChanges")).longValue())
                            .isEqualTo(scenario.name().equals("equal") ? 0L : 1L);
                    assertThat(mapList(coverage, "reasons")).hasSize(scenario.name().equals("equal") ? 0 : 1);
                    if (scenario.name().equals("excluded")) {
                        Map<?, ?> reason = mapList(coverage, "reasons").getFirst();
                        assertThat(text(reason, "reason")).isEqualTo("OUTSIDE_SOURCE_POLICY");
                        assertThat(((Number) reason.get("count")).longValue()).isEqualTo(1);
                    }
                } else if (scenario.name().equals("merge")) {
                    assertThat(changes).hasSize(1);
                    assertThat(text(changes.getFirst(), "kind")).isEqualTo("ADD");
                    assertThat(text(map(changes.getFirst(), "after"), "path")).isEqualTo("src/Side.java");
                } else {
                    Map<?, ?> service = matchingPath(changes, "src/Service.java");
                    String changeId = text(service, "changeId");
                    Map<?, ?> diff = queryParity(client, base, "get_file_diff", "/api/v1/git/file-diff",
                            Map.of("comparisonContext", context, "changeId", changeId), mapper);
                    assertThat(map(diff, "comparisonContext")).isEqualTo(context);
                    String patch = text(diff, "patch");
                    assertThat(patch).contains("+  // stable-token " + scenario.afterSource() + " 0");
                    if (!scenario.before().isEmpty()) {
                        assertThat(patch).contains("-  // stable-token " + scenario.beforeSource() + " 0");
                    }
                    if (scenario.name().equals("ordinary")) {
                        Map<?, ?> rename = matching(changes, "kind", "RENAME");
                        assertThat(text(map(rename, "before"), "path")).isEqualTo("src/RenameBefore.java");
                        assertThat(text(map(rename, "after"), "path")).isEqualTo("src/RenameAfter.java");
                        assertThat(((Number) coverage.get("excludedChanges")).longValue()).isEqualTo(3);
                        assertThat(readFile(base, map(map(discovery, "before"), "context"), "src/RenameBefore.java", mapper))
                                .isEqualTo(readFile(base, after, "src/RenameAfter.java", mapper));
                        String largePatch = exhaustParityDiff(client, base, context,
                                text(matchingPath(changes, "src/LargeDiff.java"), "changeId"), mapper);
                        assertThat(largePatch).contains("-  // changed-diff previous 0", "+  // changed-diff current 6999");
                        assertThat(exhaustParitySource(client, base, after, mapper)).isEqualTo(serviceSource("current"));
                    }
                    assertComparisonPages(client, base, context, changes, mapper);
                }
                assertThat(mapper.writeValueAsString(comparison))
                        .doesNotContain("excluded-secret.md", "excluded-only-secret", "changed-excluded", "Changed excluded");
                if (!changes.isEmpty()) {
                    Map<?, ?> first = changes.getFirst();
                    queryParity(client, base, "get_file_diff", "/api/v1/git/file-diff",
                            Map.of("comparisonContext", context, "changeId", text(first, "changeId")), mapper);
                }
                queryError(client, base, "get_file_diff", "/api/v1/git/file-diff",
                        Map.of("comparisonContext", context, "changeId", "missing-change"),
                        404, "GIT_EVIDENCE_NOT_FOUND", mapper);
                Map<String, Object> wrongContext = new java.util.HashMap<>();
                context.forEach((key, value) -> wrongContext.put((String) key, value));
                wrongContext.put("after", Map.of("kind", "REVISION", "revision", "f".repeat(40)));
                queryError(client, base, "compare_revisions", "/api/v1/git/comparisons",
                        Map.of("comparisonContext", wrongContext), 409, "REVIEW_CONTEXT_MISMATCH", mapper);
            }
            queryError(client, base, "compare_revisions", "/api/v1/git/comparisons",
                    Map.of("comparisonId", "removed-public-selector"), 400, "INVALID_ARGUMENT", mapper);
            queryError(client, base, "get_file_diff", "/api/v1/git/file-diff",
                    Map.of("changeId", "missing-context"), 400, "INVALID_ARGUMENT", mapper);
        }
    }

    private void assertSourceParity(McpSyncClient client, String base, Map<?, ?> context,
            String expected, JsonMapper mapper) throws Exception {
        Map<?, ?> source = queryParity(client, base, "read_source", "/api/v1/source",
                Map.of("context", context, "target", Map.of("kind", "FILE", "path", "src/Service.java",
                        "startLine", 3), "maxLines", 1), mapper);
        assertThat(map(source, "context")).isEqualTo(context);
        assertThat(text(source, "content")).isEqualTo("  // stable-token " + expected + " 0\n");
    }

    private void assertMetadataParity(McpSyncClient client, String base, String tool, String path,
            Map<String, Object> identity, String pin, JsonMapper mapper) throws Exception {
        Map<?, ?> defaults = queryParity(client, base, tool, path, identity, mapper);
        Map<String, Object> request = new java.util.HashMap<>(identity);
        request.put("limit", 1);
        List<Map<?, ?>> items = new ArrayList<>();
        while (true) {
            Map<?, ?> result = queryParity(client, base, tool, path, request, mapper);
            assertThat(map(result, "metadata")).isEqualTo(map(defaults, "metadata"));
            assertThat(text(map(result, "metadata"), pin)).isEqualTo(text(map(defaults, "metadata"), pin));
            items.addAll(mapList(result, "items"));
            if (!Boolean.TRUE.equals(map(result, "page").get("hasMore"))) break;
            request.put("cursor", text(map(result, "page"), "nextCursor"));
        }
        assertThat(items).isEqualTo(mapList(defaults, "items"));
        Map<String, Object> invalidCursor = new java.util.HashMap<>(identity);
        invalidCursor.put("cursor", "invalid-cursor");
        queryError(client, base, tool, path, invalidCursor, 400, "INVALID_ARGUMENT", mapper);
    }

    private void assertComparisonPages(McpSyncClient client, String base, Map<?, ?> context,
            List<Map<?, ?>> expected, JsonMapper mapper) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("comparisonContext", context, "limit", 1));
        List<Map<?, ?>> changes = new ArrayList<>();
        while (true) {
            Map<?, ?> result = queryParity(client, base, "compare_revisions", "/api/v1/git/comparisons", request, mapper);
            assertThat(map(result, "comparisonContext")).isEqualTo(context);
            changes.addAll(mapList(result, "items"));
            if (!Boolean.TRUE.equals(map(result, "page").get("hasMore"))) break;
            request.put("cursor", text(map(result, "page"), "nextCursor"));
        }
        assertThat(changes).isEqualTo(expected);
    }

    private String exhaustParityDiff(McpSyncClient client, String base, Map<?, ?> context, String changeId,
            JsonMapper mapper) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("comparisonContext", context, "changeId", changeId));
        StringBuilder patch = new StringBuilder();
        int pages = 0;
        while (true) {
            Map<?, ?> result = queryParity(client, base, "get_file_diff", "/api/v1/git/file-diff", request, mapper);
            assertThat(map(result, "comparisonContext")).isEqualTo(context);
            assertThat(text(result, "patch").getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            patch.append(text(result, "patch"));
            pages++;
            if (!result.containsKey("nextCursor")) {
                assertThat(result.get("complete")).isEqualTo(Boolean.TRUE);
                assertThat(pages).as("large patch must exercise real cursor continuation").isGreaterThan(1);
                return patch.toString();
            }
            request.put("cursor", text(result, "nextCursor"));
        }
    }

    private String exhaustParitySource(McpSyncClient client, String base, Map<?, ?> context,
            JsonMapper mapper) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("context", context, "target",
                Map.of("kind", "FILE", "path", "src/Service.java"), "maxLines", 1));
        StringBuilder source = new StringBuilder();
        boolean splitLine = false;
        while (true) {
            Map<?, ?> result = queryParity(client, base, "read_source", "/api/v1/source", request, mapper);
            assertThat(map(result, "context")).isEqualTo(context);
            assertThat(text(result, "content").getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            source.append(text(result, "content"));
            splitLine |= Boolean.FALSE.equals(result.get("endLineComplete"));
            if (!result.containsKey("nextCursor")) {
                assertThat(splitLine).as("UTF-8 long comment crosses a byte-bounded page").isTrue();
                return source.toString();
            }
            request.put("cursor", text(result, "nextCursor"));
        }
    }

    private Map<?, ?> queryParity(McpSyncClient client, String base, String tool, String path,
            Map<String, Object> request, JsonMapper mapper) throws Exception {
        Map<?, ?> http = successful(post(base, path, QUERY_TOKEN, request), mapper);
        Map<?, ?> nativeBody = mcpBody(client, tool, request, mapper);
        JsonNode nativeJson = mapper.valueToTree(nativeBody);
        JsonNode httpJson = mapper.valueToTree(http);
        assertThat(nativeJson).as("%s HTTP/native MCP", tool).isEqualTo(httpJson);
        return http;
    }

    private void queryError(McpSyncClient client, String base, String tool, String path,
            Map<String, Object> request, int status, String code, JsonMapper mapper) throws Exception {
        HttpResponse<String> http = post(base, path, QUERY_TOKEN, request);
        assertThat(http.statusCode()).as(http.body()).isEqualTo(status);
        Map<?, ?> error = mcpResult(client, tool, request, mapper, true);
        assertThat(text(error, "code")).isEqualTo(code);
        JsonNode nativeError = mapper.valueToTree(error);
        assertThat(nativeError).isEqualTo(mapper.readTree(http.body()));
    }

    private static Map<?, ?> mcpBody(McpSyncClient client, String toolName, Map<String, Object> arguments, JsonMapper mapper) {
        return mcpResult(client, toolName, arguments, mapper, false);
    }

    private static Map<?, ?> mcpResult(McpSyncClient client, String toolName, Map<String, Object> arguments,
            JsonMapper mapper, boolean error) {
        McpSchema.CallToolResult response = client.callTool(McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
        assertThat(response.isError()).as("%s structured=%s content=%s", toolName, response.structuredContent(), response.content()).isEqualTo(error);
        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst()).isInstanceOf(McpSchema.TextContent.class);
        assertThat(mapper.readTree(((McpSchema.TextContent) response.content().getFirst()).text()))
                .isEqualTo(mapper.readTree(mapper.writeValueAsString(response.structuredContent())));
        return mapper.convertValue(response.structuredContent(), Map.class);
    }

    private static McpSearchTraversal exhaustMcpSearch(McpSyncClient client, Map<?, ?> context, String query, JsonMapper mapper) {
        Map<String, Object> arguments = new java.util.HashMap<>();
        arguments.put("context", context);
        arguments.put("query", query);
        arguments.put("limit", 1);
        List<Map<?, ?>> items = new ArrayList<>();
        while (true) {
            Map<?, ?> page = mcpBody(client, "search_text", arguments, mapper);
            items.addAll(mapList(page, "items"));
            if (Boolean.TRUE.equals(page.get("scanComplete"))) {
                return new McpSearchTraversal(List.copyOf(items), page);
            }
            arguments.put("cursor", text(map(page, "page"), "nextCursor"));
        }
    }

    private static void assertNoCursor(Map<?, ?> body) {
        assertThat(body.containsKey("nextCursor")).isFalse();
    }

    private String exhaustDiff(String base, Map<?, ?> comparisonContext, String changeId, JsonMapper mapper) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("comparisonContext", comparisonContext, "changeId", changeId));
        Map<?, ?> page = successful(post(base, "/api/v1/git/file-diff", QUERY_TOKEN, request), mapper);
        StringBuilder patch = new StringBuilder();
        while (true) {
            assertThat(text(map(page, "change"), "changeId")).isEqualTo(changeId);
            patch.append(text(page, "patch"));
            if (!page.containsKey("nextCursor")) {
                return patch.toString();
            }
            request.put("cursor", text(page, "nextCursor"));
            page = successful(post(base, "/api/v1/git/file-diff", QUERY_TOKEN, request), mapper);
        }
    }

    private String exhaustFile(String base, Map<?, ?> context, JsonMapper mapper) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("context", context, "target", Map.of("kind", "FILE", "path", "src/Service.java"), "maxLines", 1));
        Map<?, ?> page = successful(post(base, "/api/v1/source", QUERY_TOKEN, request), mapper);
        StringBuilder content = new StringBuilder();
        while (true) {
            assertThat(text(page, "content").getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64 * 1024);
            assertThat(map(page, "context")).isEqualTo(context);
            content.append(text(page, "content"));
            if (!page.containsKey("nextCursor")) {
                return content.toString();
            }
            request.put("cursor", text(page, "nextCursor"));
            page = successful(post(base, "/api/v1/source", QUERY_TOKEN, request), mapper);
        }
    }

    private SearchTraversal exhaustSearch(String base, Map<?, ?> context, String query, JsonMapper mapper) throws Exception {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("context", context, "query", query, "limit", 1));
        Map<?, ?> page = successful(post(base, "/api/v1/search-text", QUERY_TOKEN, request), mapper);
        List<Map<?, ?>> items = new ArrayList<>();
        int pages = 0;
        while (true) {
            items.addAll(mapList(page, "items"));
            pages++;
            if (Boolean.TRUE.equals(page.get("scanComplete"))) {
                return new SearchTraversal(List.copyOf(items), pages);
            }
            String cursor = text(map(page, "page"), "nextCursor");
            request.put("cursor", cursor);
            HttpResponse<String> continuation = post(base, "/api/v1/search-text", QUERY_TOKEN, request);
            assertThat(continuation.statusCode()).as("query=%s cursor=%s body=%s", query, cursor, continuation.body()).isEqualTo(200);
            page = mapper.readValue(continuation.body(), Map.class);
        }
    }

    private List<Map<?, ?>> exhaustBranches(String base, String catalogId, JsonMapper mapper) throws Exception {
        return exhaustPages(base, "/api/v1/git/branches", Map.of("repositoryId", REPOSITORY_ID), "catalogId", catalogId, mapper);
    }

    private List<Map<?, ?>> exhaustHistory(String base, String historyId, JsonMapper mapper) throws Exception {
        return exhaustPages(base, "/api/v1/git/commits", Map.of("repositoryId", REPOSITORY_ID, "branch", "main"), "historyId", historyId, mapper);
    }

    private List<Map<?, ?>> exhaustPages(String base, String path, Map<String, String> identity, String identityKey, String identityValue,
            JsonMapper mapper) throws Exception {
        List<Map<?, ?>> items = new ArrayList<>();
        Map<String, Object> request = new java.util.HashMap<>(identity);
        request.put("limit", 1);
        while (true) {
            Map<?, ?> page = successful(post(base, path, QUERY_TOKEN, request), mapper);
            assertThat(text(map(page, "metadata"), identityKey)).isEqualTo(identityValue);
            items.addAll(mapList(page, "items"));
            if (!Boolean.TRUE.equals(map(page, "page").get("hasMore"))) return List.copyOf(items);
            request.put("cursor", text(map(page, "page"), "nextCursor"));
        }
    }

    private void assertUnchangedAndDeletedFiles(String base, Map<?, ?> beforeContext, Map<?, ?> afterContext, JsonMapper mapper) throws Exception {
        assertThat(readFile(base, afterContext, "src/Caller.java", mapper)).isEqualTo("class Caller { int call() { return Service.version(); } }\n");
        assertThat(readFile(base, afterContext, "src/ServiceTest.java", mapper)).isEqualTo("class ServiceTest { }\n");
        assertThat(readFile(base, beforeContext, "src/DeletedPrevious.java", mapper)).isEqualTo("class DeletedPrevious { }\n");
        for (String path : List.of("config/review.properties", "docs/review.md", "assets/binary.dat")) {
            HttpResponse<String> excluded = post(base, "/api/v1/source", QUERY_TOKEN,
                    Map.of("context", afterContext, "target", Map.of("kind", "FILE", "path", path)));
            assertThat(excluded.statusCode()).isEqualTo(404);
            assertThat(excluded.body()).doesNotContain("synthetic", "Synthetic review fixture");
        }
        Map<?, ?> codeFiles = successful(post(base, "/api/v1/files", QUERY_TOKEN, Map.of("context", afterContext, "directory", "src")), mapper);
        assertThat(mapList(codeFiles, "items")).extracting(item -> text(item, "path"))
                .contains("src/Caller.java", "src/Service.java").doesNotContain("config/review.properties", "docs/review.md");
    }

    private String readFile(String base, Map<?, ?> context, String path, JsonMapper mapper) throws Exception {
        Map<?, ?> page = successful(post(base, "/api/v1/source", QUERY_TOKEN,
                Map.of("context", context, "target", Map.of("kind", "FILE", "path", path))), mapper);
        assertNoCursor(page);
        return text(page, "content");
    }

    private Map<?, ?> completedRequest(String base, String requestId, JsonMapper mapper, RunningProcess indexer) throws Exception {
        return completedRequest(base, REPOSITORY_ID, requestId, mapper, indexer);
    }

    private Map<?, ?> completedRequest(String base, String repositoryId, String requestId,
            JsonMapper mapper, RunningProcess indexer) throws Exception {
        Instant deadline = Instant.now().plus(JOB_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            assertThat(indexer.process().isAlive()).as("Indexer exited while polling: %s", Files.readString(indexer.log())).isTrue();
            HttpResponse<String> response = get(base, "/index/repositories/" + repositoryId + "/jobs?requestId=" + requestId, ADMIN_TOKEN);
            if (response.statusCode() == 200) {
                Map<?, ?> status = mapper.readValue(response.body(), Map.class);
                if ("COMPLETE".equals(status.get("phase"))) {
                    return status;
                }
                if ("FAILED".equals(status.get("phase"))) {
                    throw new AssertionError("request " + requestId + " failed: " + status.get("failureCategory")
                            + "\n" + Files.readString(indexer.log()));
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("request did not complete: " + requestId);
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

    private HttpResponse<String> post(String base, String path, String token, Map<String, Object> body) throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        return postUri(base + path, token, mapper.writeValueAsString(body));
    }

    private HttpResponse<String> postUri(String uri, String token, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30))
                .header(TOKEN_HEADER, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String base, String path, String token) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header(TOKEN_HEADER, token).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
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
        return items.stream().filter(item -> item.get("after") instanceof Map<?, ?> after && path.equals(after.get("path"))).findFirst()
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

    private record Topology(String side, String merge, String policy) { }

    private record ReviewCase(String name, String reviewId, Map<String, String> selection, String before,
            String after, String beforeSource, String afterSource) { }

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
                        assertThat(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                                .as("forced application shutdown").isTrue();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }
}
