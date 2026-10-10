package com.java.semantic.indexer.uat;

import com.java.semantic.indexer.source.DurableSourceFiles;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.java.semantic.indexer.uat.NativeJourneySupport.base;
import static com.java.semantic.indexer.uat.NativeJourneySupport.freePort;
import static com.java.semantic.indexer.uat.NativeJourneySupport.git;
import static com.java.semantic.indexer.uat.NativeJourneySupport.stop;
import static org.assertj.core.api.Assertions.assertThat;

/** Two real app jars, exact Git revisions and child-built Relations; no target repository execution. */
@Tag("deployed-it")
class SourceRelationsJourneyIT {
    private static final String REPOSITORY = "relations";
    private static final String DEMO = "src/demo/Demo.java";
    private static final String SCOPES = "src/demo/Scopes.java";
    private static final String COMPACT = "src/demo/R.java";
    private static final String APPROVED = "src/demo/ApprovedUse.java";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Path root = Path.of(System.getProperty("source.journey.root"));
    private final Path work = root.resolve("work/relations");
    private final Path artifacts = root.resolve("artifacts/relations");
    private final Path checkout = work.resolve("checkout");
    private final Path remote = work.resolve("remote.git");
    private final Path published = work.resolve("source-published");
    private final Path indexes = work.resolve("index-published");
    private final Path admin = work.resolve("source-admin");
    private final String adminToken = UUID.randomUUID().toString();
    private final String readToken = UUID.randomUUID().toString();
    private Path approvedJar;

    @Test void exact_A_B_relations_survive_rebuild_failure_and_cold_query_with_explicit_generation_expiry() throws Exception {
        Files.createDirectories(work);
        Files.createDirectories(artifacts);
        approvedJar = NativeJourneySupport.tinyApprovedJar(work.resolve("approved-dependency"));
        Files.createDirectories(checkout.resolve("src/demo"));
        git(work, "init", "--bare", remote.toString());
        git(checkout, "init", "-b", "main");
        git(checkout, "config", "user.name", "Owned Relations Journey");
        git(checkout, "config", "user.email", "relations@example.invalid");
        git(checkout, "remote", "add", "origin", remote.toString());
        Files.writeString(checkout.resolve("README.md"), "Owned static Relations fixture.\n");
        Files.writeString(checkout.resolve("src/demo/Sink.java"), "package demo; interface Sink { void save(Object value); }\n");
        Files.writeString(checkout.resolve(DEMO), demo(false));
        Files.writeString(checkout.resolve(SCOPES), """
                package demo;
                class Scopes {
                    String value = "field";
                    String read(String value) { return value + this.value; }
                    Runnable deferred = () -> consume(value);
                    void consume(String input) {}
                }
                """);
        Files.writeString(checkout.resolve(COMPACT),
                "package demo; record R(int value) { R { validate(value); } static void validate(int value) {} }\n");
        Files.writeString(checkout.resolve(APPROVED), """
                package demo;
                class ApprovedUse {
                    void run() {
                        owned.ApprovedApi.validate(1);
                        java.util.List.of("owned");
                    }
                }
                """);
        Files.writeString(checkout.resolve("src/demo/Evidence.java"), """
                package demo;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.kafka.annotation.KafkaListener;
                class Evidence {
                    @GetMapping("/vip") @KafkaListener(topics="vip-events")
                    void query(java.sql.Connection connection, String dynamic) throws Exception {
                        connection.prepareStatement("select tier from vip where user_id = ?");
                        connection.prepareStatement(dynamic);
                    }
                }
                """);
        String a = commit("A");
        int indexPort = freePort();
        int queryPort = freePort();
        String index = base(indexPort);
        String read = base(queryPort);
        Process indexer = null;
        Process query = null;
        JsonNode contextA;
        JsonNode contextB;
        JsonNode generationA;
        JsonNode generationB;
        JsonNode rebuilt;
        String b;
        try {
            indexer = launch(true, indexPort, false, false);
            ready(indexer, index, "/index/repositories/relations/jobs?requestId=" + UUID.randomUUID(), adminToken, 404);
            query = launch(false, queryPort, false, false);
            ready(query, read, "/api/v1/repositories", readToken, 200);
            try (McpSyncClient writer = mcp(index, adminToken); McpSyncClient reader = mcp(read, readToken)) {
                prepare(writer, "prepare_source", Map.of(), "source-a");
                contextA = call(reader, "get_context", Map.of("repositoryId", REPOSITORY)).get("context");
                assertThat(contextA.get("revision").asString()).isEqualTo(a);
                generationA = prepare(writer, "prepare_semantic_index", Map.of("revision", a), "index-a");
                JsonNode first = relations(reader, read, contextA, generationA, "calls-a", 20, null);
                assertOutgoingCalls(first, false);
                verifyRaw(reader, contextA, a, first);
                verifyFramework(reader, read, contextA, generationA, a);
                verifyUnsupportedFile(reader, read, contextA, generationA);
                verifyScopes(reader, contextA, generationA, a);
                verifyCompactConstructor(reader, read, contextA, generationA, a);
                verifyApprovedJava21(reader, read, contextA, generationA, a);
                JsonNode page = relations(reader, read, contextA, generationA, "a-page1", 1, null);
                assertThat(page.get("page").get("hasMore").asBoolean()).isTrue();
                String cursor = page.get("page").get("nextCursor").asString();

                Files.writeString(checkout.resolve(DEMO), demo(true));
                b = commit("B");
                prepare(writer, "prepare_source", Map.of(), "source-b");
                contextB = call(reader, "get_context", Map.of("repositoryId", REPOSITORY)).get("context");
                assertThat(contextB.get("revision").asString()).isEqualTo(b);
                generationB = prepare(writer, "prepare_semantic_index", Map.of("revision", b), "index-b");
                assertOutgoingCalls(relations(reader, read, contextB, generationB, "calls-b", 20, null), true);
                JsonNode continued = relations(reader, read, contextA, generationA, "a-page2-while-b", 1, cursor);
                assertThat(continued.get("indexGeneration")).isEqualTo(generationA.get("indexGeneration"));
                assertThat(continued.get("items").get(0).get("relationId"))
                        .isNotEqualTo(page.get("items").get(0).get("relationId"));
                assertOutgoingCalls(relations(reader, read, contextA, generationA, "calls-a-while-b", 20, null), false);
                rebuilt = prepare(writer, "prepare_semantic_index", Map.of("revision", b), "index-b-rebuilt");
                assertThat(rebuilt.get("indexGeneration")).isNotEqualTo(generationB.get("indexGeneration"));
                assertOutgoingCalls(relations(reader, read, contextB, generationB, "retained-b", 20, null), true);
                verifyRaw(reader, contextB, b, relations(reader, read, contextB, rebuilt, "rebuilt-b", 20, null));
            }
            stop(indexer);
            // Syntax-only admission avoids the earlier input-freeze refusal boundary.
            // Its durable capacity failure must still preserve the existing profile-3 READY index.
            indexer = launch(true, indexPort, false, true);
            ready(indexer, index, "/index/repositories/relations/jobs?requestId=" + UUID.randomUUID(), adminToken, 404);
            try (McpSyncClient writer = mcp(index, adminToken); McpSyncClient reader = mcp(read, readToken)) {
                String original = requestId("capacity-refusal");
                call(writer, "prepare_semantic_index", Map.of("repositoryId", REPOSITORY, "revision", b, "requestId", original));
                JsonNode failed = terminal(writer, original);
                assertThat(failed.get("phase").asString()).isEqualTo("FAILED");
                assertThat(failed.get("failureCode").asString()).isEqualTo("CAPACITY_EXCEEDED");
                assertThat(call(reader, "get_context", Map.of("repositoryId", REPOSITORY)).get("index").get("indexGeneration"))
                        .isEqualTo(rebuilt.get("indexGeneration"));
                assertOutgoingCalls(relations(reader, read, contextB, rebuilt, "ready-after-failure", 20, null), true);
            }
            stop(indexer);
            indexer = launch(true, indexPort, true, false);
            ready(indexer, index, "/index/repositories/relations/jobs?requestId=" + UUID.randomUUID(), adminToken, 404);
            try (McpSyncClient reader = mcp(read, readToken)) {
                Map<String, Object> expired = relationArguments(contextB, generationB, 20, null);
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                McpSchema.CallToolResult result;
                do {
                    result = reader.callTool(new McpSchema.CallToolRequest("find_relations", expired));
                    if (Boolean.TRUE.equals(result.isError())) break;
                    Thread.sleep(100);
                } while (System.nanoTime() < deadline);
                assertThat(result.isError()).isTrue();
                assertThat(mapper.valueToTree(result.structuredContent()).get("code").asString()).isEqualTo("INDEX_GENERATION_EXPIRED");
                HttpResponse<String> response = request(read, "POST", "/api/v1/relations", mapper.writeValueAsString(expired), readToken);
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(mapper.readTree(response.body()).get("code").asString()).isEqualTo("INDEX_GENERATION_EXPIRED");
            }
            stop(indexer); indexer = null;
            stop(query);
            query = launch(false, queryPort, false, false);
            ready(query, read, "/api/v1/repositories", readToken, 200);
            try (McpSyncClient reader = mcp(read, readToken)) {
                assertOutgoingCalls(relations(reader, read, contextA, generationA, "cold-a", 20, null), false);
                JsonNode cold = relations(reader, read, contextB, rebuilt, "cold-b", 20, null);
                assertOutgoingCalls(cold, true);
                verifyRaw(reader, contextB, b, cold);
                verifyCompactConstructor(reader, read, contextB, rebuilt, b);
                verifyApprovedJava21(reader, read, contextB, rebuilt, b);
            }
        } finally {
            stop(query);
            stop(indexer);
        }
    }

    private static String demo(boolean changed) {
        return """
                package demo;
                class Demo implements Sink {
                    public void save(Object value) {}
                    public void save(String value) {}
                    void run(Sink target, Missing unknown) {
                        target.save(1);
                        %s
                        java.util.List.of("x");
                        unknown.work();
                    }
                }
                """.formatted(changed ? "save((Object) \"x\");" : "save(\"x\");");
    }

    private String commit(String message) throws Exception {
        git(checkout, "add", "."); git(checkout, "commit", "-m", message); git(checkout, "push", "origin", "main");
        return git(checkout, "rev-parse", "HEAD").strip();
    }

    private JsonNode prepare(McpSyncClient writer, String operation, Map<String, Object> fields, String label) throws Exception {
        String original = requestId(label);
        Map<String, Object> input = new HashMap<>(fields);
        input.put("repositoryId", REPOSITORY); input.put("requestId", original);
        JsonNode accepted = call(writer, operation, input);
        JsonNode completed = terminal(writer, original);
        assertThat(completed.get("phase").asString()).as(completed.toString()).isEqualTo("COMPLETE");
        assertThat(completed.get("jobId")).isEqualTo(accepted.get("jobId"));
        Files.writeString(artifacts.resolve(label + ".json"), mapper.writeValueAsString(completed));
        return completed;
    }

    private String requestId(String label) throws IOException {
        String original = UUID.randomUUID().toString();
        Files.writeString(artifacts.resolve(label + "-request.json"), mapper.writeValueAsString(Map.of("repositoryId", REPOSITORY, "requestId", original)));
        return original;
    }

    private JsonNode terminal(McpSyncClient writer, String original) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode job = call(writer, "get_job", Map.of("repositoryId", REPOSITORY, "requestId", original));
            if (List.of("COMPLETE", "FAILED").contains(job.get("phase").asString())) return job;
            Thread.sleep(100);
        }
        throw new AssertionError("Original Relations request did not finish within its native journey deadline");
    }

    private Map<String, Object> relationArguments(JsonNode context, JsonNode job, int limit, String cursor) {
        Map<String, Object> input = new HashMap<>();
        input.put("context", context); input.put("indexGeneration", job.get("indexGeneration").asString());
        input.put("anchor", Map.of("kind", "FILE", "path", DEMO)); input.put("direction", "OUTGOING");
        input.put("relationKinds", List.of("CALL")); input.put("limit", limit);
        if (Objects.nonNull(cursor)) input.put("cursor", cursor);
        return input;
    }

    private JsonNode relations(McpSyncClient reader, String base, JsonNode context, JsonNode job,
            String label, int limit, String cursor) throws Exception {
        Map<String, Object> input = relationArguments(context, job, limit, cursor);
        JsonNode result = call(reader, "find_relations", input);
        HttpResponse<String> response = request(base, "POST", "/api/v1/relations", mapper.writeValueAsString(input), readToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body())).isEqualTo(result);
        assertThat(result.get("context")).isEqualTo(context);
        assertThat(result.get("indexGeneration")).isEqualTo(job.get("indexGeneration"));
        Files.writeString(artifacts.resolve(label + ".json"), mapper.writeValueAsString(result));
        return result;
    }

    private void assertOutgoingCalls(JsonNode response, boolean changed) {
        List<String> signatures = new ArrayList<>();
        List<JsonNode> unresolved = new ArrayList<>();
        for (JsonNode fact : response.get("items")) {
            if (fact.get("resolutionState").asString().equals("STATIC_RESOLVED")) {
                JsonNode target = fact.get("target");
                signatures.add(target.get("kind").asString().equals("SOURCE_SYMBOL")
                        ? target.get("source").get("qualifiedSignature").asString() : target.get("external").get("signature").asString());
            } else unresolved.add(fact);
        }
        assertThat(signatures).containsExactlyInAnyOrder("demo.Sink.save(java.lang.Object)",
                changed ? "demo.Demo.save(java.lang.Object)" : "demo.Demo.save(java.lang.String)", "java.util.List.of(E)");
        assertThat(unresolved).singleElement().satisfies(fact -> {
            assertThat(fact.get("resolutionState").asString()).isEqualTo("UNRESOLVED");
            assertThat(fact.get("unresolvedReason").asString()).isEqualTo("MISSING_TYPE");
            assertThat(fact.hasNonNull("target")).isFalse();
        });
    }

    private void verifyRaw(McpSyncClient reader, JsonNode context, String revision, JsonNode relations) throws Exception {
        byte[] expected = verifyRawFile(reader, context, revision, DEMO);
        for (JsonNode fact : relations.get("items")) {
            JsonNode span = fact.get("source").get("range");
            int start = span.get("startByte").asInt();
            int end = span.get("endByte").asInt();
            String spelling = new String(expected, start, end - start, StandardCharsets.UTF_8);
            assertThat(spelling).isIn("target.save(1)", "save(\"x\")", "save((Object) \"x\")", "java.util.List.of(\"x\")", "unknown.work()");
        }
    }

    private byte[] verifyRawFile(McpSyncClient reader, JsonNode context, String revision, String path) throws Exception {
        byte[] expected = git(checkout, "show", revision + ":" + path).getBytes(StandardCharsets.UTF_8);
        JsonNode raw = call(reader, "read_source", Map.of("context", context, "path", path, "view", "raw"));
        StringBuilder actual = new StringBuilder();
        for (JsonNode segment : raw.get("segments")) actual.append(segment.get("content").asString());
        assertThat(actual.toString().getBytes(StandardCharsets.UTF_8)).isEqualTo(expected);
        return expected;
    }

    private void verifyScopes(McpSyncClient reader, JsonNode context, JsonNode job, String revision) throws Exception {
        byte[] raw = verifyRawFile(reader, context, revision, SCOPES);
        Map<String, Object> input = relationArguments(context, job, 100, null);
        input.put("anchor", Map.of("kind", "FILE", "path", SCOPES));
        input.put("relationKinds", List.of("CALL", "REFERENCE"));
        JsonNode result = call(reader, "find_relations", input);
        List<String> fieldUses = new ArrayList<>();
        List<JsonNode> calls = new ArrayList<>();
        for (JsonNode fact : result.get("items")) {
            if (fact.get("kind").asString().equals("CALL")) calls.add(fact);
            JsonNode target = fact.get("target");
            if (Objects.nonNull(target) && !target.isNull() && target.get("kind").asString().equals("SOURCE_SYMBOL")
                    && target.get("source").get("declaration").get("kind").asString().equals("FIELD")) {
                assertThat(target.get("source").get("declaration").get("name").asString()).isEqualTo("value");
                JsonNode span = fact.get("source").get("range");
                fieldUses.add(new String(raw, span.get("startByte").asInt(),
                        span.get("endByte").asInt() - span.get("startByte").asInt(), StandardCharsets.UTF_8));
            }
        }
        // The shadowing parameter is not a field target; both written field uses remain.
        assertThat(fieldUses).containsExactlyInAnyOrder("this.value", "value");
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.get("resolutionState").asString()).isEqualTo("STATIC_RESOLVED");
            assertThat(call.get("source").get("executionScope").get("kind").asString()).isEqualTo("LAMBDA");
            assertThat(call.get("target").get("source").get("qualifiedSignature").asString())
                    .isEqualTo("demo.Scopes.consume(java.lang.String)");
        });
        Files.writeString(artifacts.resolve("scopes-a.json"), mapper.writeValueAsString(result));
    }

    private void verifyCompactConstructor(McpSyncClient reader, String base, JsonNode context, JsonNode job, String revision) throws Exception {
        byte[] raw = verifyRawFile(reader, context, revision, COMPACT);
        JsonNode outline = call(reader, "get_outline", Map.of("context", context, "path", COMPACT,
                "indexGeneration", job.get("indexGeneration").asString()));
        List<JsonNode> constructors = new ArrayList<>();
        for (JsonNode declaration : outline.get("items")) {
            if (declaration.get("kind").asString().equals("CONSTRUCTOR")) constructors.add(declaration);
        }
        assertThat(constructors).hasSize(1);
        JsonNode constructor = constructors.getFirst();
        Map<String, Object> input = relationArguments(context, job, 20, null);
        input.put("anchor", Map.of("kind", "FILE", "path", COMPACT));
        JsonNode file = call(reader, "find_relations", input);
        assertThat(file.get("items").size()).isEqualTo(1);
        JsonNode occurrence = file.get("items").get(0);
        JsonNode site = occurrence.get("source");
        JsonNode span = site.get("range");
        assertThat(new String(raw, span.get("startByte").asInt(),
                span.get("endByte").asInt() - span.get("startByte").asInt(), StandardCharsets.UTF_8)).isEqualTo("validate(value)");
        assertThat(site.get("executionScope").get("kind").asString()).isEqualTo("CONSTRUCTOR");
        assertThat(site.get("containerSymbolId")).isEqualTo(constructor.get("symbolId"));
        for (JsonNode contained : List.of(span, site.get("executionScope").get("range"))) {
            assertThat(contained.get("startByte").asLong()).isGreaterThanOrEqualTo(constructor.get("range").get("startByte").asLong());
            assertThat(contained.get("endByte").asLong()).isLessThanOrEqualTo(constructor.get("range").get("endByte").asLong());
        }
        input.put("anchor", Map.of("kind", "SOURCE_SYMBOL", "symbolId", constructor.get("symbolId").asString()));
        JsonNode outgoing = call(reader, "find_relations", input);
        assertThat(outgoing.get("items")).isEqualTo(file.get("items"));
        HttpResponse<String> response = request(base, "POST", "/api/v1/relations", mapper.writeValueAsString(input), readToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body())).isEqualTo(outgoing);
        Files.writeString(artifacts.resolve("compact-constructor-" + revision + ".json"), mapper.writeValueAsString(outgoing));
    }

    private void verifyApprovedJava21(McpSyncClient reader, String base, JsonNode context, JsonNode job, String revision) throws Exception {
        byte[] raw = verifyRawFile(reader, context, revision, APPROVED);
        Map<String, Object> input = relationArguments(context, job, 20, null);
        input.put("anchor", Map.of("kind", "FILE", "path", APPROVED));
        JsonNode file = call(reader, "find_relations", input);
        assertThat(file.get("profile").get("schemaVersion").asInt()).isEqualTo(3);
        assertThat(file.get("items").size()).isEqualTo(2);
        HttpResponse<String> response = request(base, "POST", "/api/v1/relations", mapper.writeValueAsString(input), readToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(mapper.readTree(response.body())).isEqualTo(file);
        List<String> spellings = new ArrayList<>();
        for (JsonNode fact : file.get("items")) {
            assertThat(fact.get("resolutionState").asString()).isEqualTo("STATIC_RESOLVED");
            JsonNode span = fact.get("source").get("range");
            String spelling = new String(raw, span.get("startByte").asInt(),
                    span.get("endByte").asInt() - span.get("startByte").asInt(), StandardCharsets.UTF_8);
            spellings.add(spelling);
            JsonNode target = fact.get("target").get("external");
            assertThat(target.get("artifactId").asString()).isEqualTo("java21");
            if (spelling.equals("owned.ApprovedApi.validate(1)")) {
                assertThat(target.hasNonNull("module")).isFalse();
                assertThat(target.get("artifactDigest").asString()).isEqualTo(
                        DurableSourceFiles.sha256(approvedJar));
                assertThat(target.get("qualifiedOwner").asString()).isEqualTo("owned.ApprovedApi");
                assertThat(target.get("signature").asString()).isEqualTo("owned.ApprovedApi.validate(int)");
            } else {
                assertThat(target.get("module").asString()).isEqualTo("java.base");
                assertThat(target.get("artifactDigest")).isEqualTo(file.get("resolutionProvenance").get("platform").get("modulesDigest"));
            }
            input.put("anchor", Map.of("kind", "EXTERNAL_TARGET", "targetId", target.get("targetId").asString()));
            input.put("direction", "INCOMING");
            JsonNode incoming = call(reader, "find_relations", input);
            List<JsonNode> incomingFacts = new ArrayList<>();
            incoming.get("items").forEach(incomingFacts::add);
            assertThat(incoming.get("page").get("hasMore").asBoolean()).isFalse();
            if (!target.hasNonNull("module")) {
                assertThat(incomingFacts).containsExactly(fact);
            } else {
                assertThat(incomingFacts).contains(fact);
                assertThat(incomingFacts).extracting(item -> item.get("source").get("path").asString())
                        .containsExactlyInAnyOrder(APPROVED, DEMO);
                JsonNode demoFact = incomingFacts.stream()
                        .filter(item -> item.get("source").get("path").asString().equals(DEMO))
                        .findFirst().orElseThrow();
                assertThat(demoFact.get("target")).isEqualTo(fact.get("target"));
                assertThat(demoFact.get("resolutionState").asString()).isEqualTo("STATIC_RESOLVED");
                byte[] demoRaw = verifyRawFile(reader, context, revision, DEMO);
                JsonNode demoSite = demoFact.get("source");
                assertThat(demoSite.get("blobId").asString()).isEqualTo(git(checkout, "rev-parse", revision + ":" + DEMO).strip());
                assertThat(demoSite.get("contentDigest").asString()).isEqualTo(DurableSourceFiles.sha256(demoRaw));
                assertThat(demoSite.get("byteLength").asLong()).isEqualTo(demoRaw.length);
                JsonNode demoSpan = demoSite.get("range");
                assertThat(new String(demoRaw, demoSpan.get("startByte").asInt(),
                        demoSpan.get("endByte").asInt() - demoSpan.get("startByte").asInt(), StandardCharsets.UTF_8))
                        .isEqualTo("java.util.List.of(\"x\")");
            }
            response = request(base, "POST", "/api/v1/relations", mapper.writeValueAsString(input), readToken);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(mapper.readTree(response.body())).isEqualTo(incoming);
        }
        assertThat(spellings).containsExactlyInAnyOrder("owned.ApprovedApi.validate(1)", "java.util.List.of(\"owned\")");
        Files.writeString(artifacts.resolve("approved-java21-" + revision + ".json"), mapper.writeValueAsString(file));
    }

    private void verifyFramework(McpSyncClient reader, String base, JsonNode context, JsonNode job, String revision) throws Exception {
        byte[] raw = verifyRawFile(reader, context, revision, "src/demo/Evidence.java");
        Map<String, Object> input = relationArguments(context, job, 100, null);
        input.put("anchor", Map.of("kind", "FILE", "path", "src/demo/Evidence.java"));
        input.put("relationKinds", List.of("HTTP_EVIDENCE", "EVENT_EVIDENCE", "DB_EVIDENCE"));
        JsonNode result = call(reader, "find_relations", input);
        List<String> occurrences = new ArrayList<>();
        for (JsonNode fact : result.get("items")) {
            JsonNode span = fact.get("source").get("range");
            String spelling = new String(raw, span.get("startByte").asInt(),
                    span.get("endByte").asInt() - span.get("startByte").asInt(), StandardCharsets.UTF_8);
            occurrences.add(spelling);
            assertThat(fact.get("resolutionState").asString()).isEqualTo("CANDIDATE");
            JsonNode evidence = fact.get("framework");
            switch (fact.get("kind").asString()) {
                case "HTTP_EVIDENCE" -> assertThat(evidence.get("literalValues").get("value"))
                        .isEqualTo(mapper.valueToTree(List.of("/vip")));
                case "EVENT_EVIDENCE" -> assertThat(evidence.get("literalValues").get("topics"))
                        .isEqualTo(mapper.valueToTree(List.of("vip-events")));
                case "DB_EVIDENCE" -> {
                    assertThat(evidence.get("recognitionBasis").asString()).isEqualTo("STATIC_API");
                    if (spelling.equals("connection.prepareStatement(dynamic)")) {
                        assertThat(evidence.get("literalValues").has("sql")).isFalse();
                        assertThat(evidence.get("unresolvedMembers")).isEqualTo(mapper.valueToTree(List.of("sql")));
                    } else {
                        assertThat(evidence.get("literalValues").get("sql"))
                                .isEqualTo(mapper.valueToTree(List.of("select tier from vip where user_id = ?")));
                    }
                }
                default -> throw new AssertionError("Unexpected framework relation kind");
            }
        }
        assertThat(occurrences).containsExactlyInAnyOrder("@GetMapping(\"/vip\")", "@KafkaListener(topics=\"vip-events\")",
                "connection.prepareStatement(\"select tier from vip where user_id = ?\")", "connection.prepareStatement(dynamic)");
        HttpResponse<String> response = request(base, "POST", "/api/v1/relations", mapper.writeValueAsString(input), readToken);
        assertThat(mapper.readTree(response.body())).isEqualTo(result);
    }

    private void verifyUnsupportedFile(McpSyncClient reader, String base, JsonNode context, JsonNode job) throws Exception {
        Map<String, Object> input = relationArguments(context, job, 20, null);
        input.put("anchor", Map.of("kind", "FILE", "path", "README.md"));
        JsonNode result = call(reader, "find_relations", input);
        assertThat(result.get("file").get("status").asString()).isEqualTo("UNSUPPORTED");
        assertThat(result.get("file").get("file").get("javaFile").asBoolean()).isFalse();
        assertThat(result.get("items").isEmpty()).isTrue();
        HttpResponse<String> response = request(base, "POST", "/api/v1/relations", mapper.writeValueAsString(input), readToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body())).isEqualTo(result);
        Files.writeString(artifacts.resolve("unsupported-file.json"), mapper.writeValueAsString(result));
    }

    private JsonNode call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        assertThat(result.isError()).as(tool + ": " + result).isFalse();
        JsonNode structured = mapper.valueToTree(result.structuredContent());
        assertThat(mapper.readTree(((McpSchema.TextContent) result.content().getFirst()).text())).isEqualTo(structured);
        assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(524_288);
        if (tool.equals("find_relations")) assertThat(mapper.writeValueAsBytes(structured).length).isLessThanOrEqualTo(163_840);
        return structured;
    }

    private McpSyncClient mcp(String base, String token) {
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(base + "/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header("X-Api-Token", token)).build())
                .requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize(); return client;
    }

    private HttpResponse<String> request(String base, String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).header("X-Api-Token", token);
        if (method.equals("POST")) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)); else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ready(Process process, String base, String path, String token, int status) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try { if (request(base, "GET", path, "", token).statusCode() == status) return; }
            catch (IOException starting) { /* socket not bound yet */ }
            Thread.sleep(100);
        }
        throw new AssertionError("Relations service failed to start; inspect its owned process log");
    }

    private Process launch(boolean indexer, int port, boolean retention, boolean capacityRefusal) throws IOException {
        String name = (indexer ? "indexer-" : "query-") + UUID.randomUUID();
        Path nativeRoot = Files.createTempDirectory(work, name, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        List<String> args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx512m", "-XX:ActiveProcessorCount=2", "-Dorg.sqlite.tmpdir=" + nativeRoot, "-jar",
                System.getProperty(indexer ? "source.journey.indexer.jar" : "source.journey.query.jar"),
                "--server.address=127.0.0.1", "--server.port=" + port));
        if (indexer) args.addAll(List.of("--semantic.source-admin-root=" + admin, "--semantic.source-published-root=" + published,
                "--semantic.index-published-root=" + indexes, "--semantic.source-retention.enabled=" + retention,
                "--semantic.source-retention.interval=1s", "--semantic.source-capacity.max-managed-bytes=" + (capacityRefusal ? 1 : 0),
                "--semantic.repositories.relations.url=" + remote.toUri(), "--semantic.repositories.relations.default-branch=main",
                "--semantic.repositories.relations.display-name=Relations", "--semantic.repositories.relations.relations.enabled=" + !capacityRefusal,
                "--semantic.repositories.relations.relations.source-roots[0]=src"));
        if (indexer) args.addAll(List.of(
                "--semantic.repositories.relations.relations.dependencies[0].id=java21",
                "--semantic.repositories.relations.relations.dependencies[0].path=" + approvedJar,
                "--semantic.repositories.relations.relations.dependencies[0].sha256="
                        + DurableSourceFiles.sha256(approvedJar)));
        else args.addAll(List.of("--semantic.query.source.published-root=" + published, "--semantic.query.index.published-root=" + indexes,
                "--semantic.query.source.allowed-repositories[0]=relations", "--semantic.query.source.rg-executable=" + System.getProperty("source.test.rg")));
        ProcessBuilder process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(work.resolve(name + ".log").toFile());
        process.environment().clear();
        process.environment().put(indexer ? "SEMANTIC_INDEXER_ADMIN_TOKEN" : "SEMANTIC_QUERY_API_TOKEN", indexer ? adminToken : readToken);
        return process.start();
    }
}
