package com.java.semantic.document.uat;

import com.java.semantic.document.support.DocumentFixture;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real executable/HTTP proof, separate from external-agent source conclusions. */
@Tag("deployed-it")
class DocumentMcpJourneyIT {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private int requestId;

    @Test
    void packagedStatelessServicePreservesExactDocumentsAcrossUpdatesWithdrawalAndRestart() throws Exception {
        Path runRoot = Path.of(Objects.requireNonNull(System.getProperty("document.journey.root")));
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("document.journey.jar")));
        Path work = Files.createDirectories(runRoot.resolve("work"));
        Path artifacts = Files.createDirectories(runRoot.resolve("artifacts"));
        String token = UUID.randomUUID().toString();
        List<String> checks = new ArrayList<>();
        String first;
        String second;
        String rejected;
        String oldCursor;
        String body = "# HIT A\r\n舊規則\r\n# HIT B\r\n尾端";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                DocumentFixture fixture = new DocumentFixture(work.resolve("documents"))) {
            first = fixture.commit(Map.of(DocumentFixture.GUIDE, body));
            fixture.publish(first, first);
            ObjectId firstBlob = Objects.requireNonNull(fixture.git.getRepository().resolve(first + ":" + DocumentFixture.GUIDE));
            byte[] original = fixture.git.getRepository().open(firstBlob).getBytes();
            try (OwnedService service = new OwnedService(jar, fixture, token, work.resolve("first.log"))) {
                service.awaitReady(client);
                assertThat(send(client, service.uri, "", "tools/list", Map.of()).statusCode()).isEqualTo(401);
                assertThat(send(client, service.uri, "wrong", "tools/list", Map.of()).statusCode()).isEqualTo(401);
                checks.add("missing/wrong document token rejected before tool access");
                initialize(client, service.uri, token);
                JsonNode tools = rpc(client, service.uri, token, "tools/list", Map.of()).path("tools");
                List<String> names = new ArrayList<>();
                for (JsonNode tool : tools) {
                    names.add(tool.path("name").stringValue());
                    assertThat(tool.path("annotations").path("readOnlyHint").booleanValue()).isTrue();
                }
                assertThat(names).containsExactlyInAnyOrder("search_documents", "read_document");
                checks.add("only two real read-only MCP tools listed");
                JsonNode page = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                assertSuccess(page);
                JsonNode pageData = page.path("structuredContent");
                assertThat(pageData.path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                oldCursor = pageData.path("page").path("nextCursor").stringValue();
                assertThat(pageData.path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(1);
                JsonNode read = call(client, service.uri, token, "read_document", readArguments(first, 1));
                assertSuccess(read);
                JsonNode data = read.path("structuredContent");
                assertThat(data.path("content").stringValue().getBytes(StandardCharsets.UTF_8)).isEqualTo(original);
                assertThat(data.path("blobId").stringValue()).isEqualTo(firstBlob.name());
                assertThat(data.path("contentDigest").stringValue()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
                assertThat(data.path("byteLength").intValue()).isEqualTo(original.length);
                assertThat(data.path("trust").stringValue()).isEqualTo("NOT_VERIFIED");
                assertThat(data.path("reviewStatus").stringValue()).isEqualTo("DRAFT");
                assertThat(data.path("collectionPurpose").stringValue()).isEqualTo("TEST_ONLY");
                checks.add("full document bytes/CRLF/EOF/blob/digest and test-only provenance match real Git");
                second = fixture.commit(Map.of(DocumentFixture.GUIDE, "# HIT Updated\n新規則"));
                fixture.publish(second, first, second);
                JsonNode current = call(client, service.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(current);
                assertThat(current.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                JsonNode continued = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", oldCursor));
                assertSuccess(continued);
                assertThat(continued.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                assertThat(continued.path("structuredContent").path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(3);
                JsonNode oldRead = call(client, service.uri, token, "read_document", readArguments(first, 3));
                assertSuccess(oldRead);
                assertThat(oldRead.path("structuredContent").path("content").stringValue()).isEqualTo(body);
                checks.add("new search uses D2; D1 continuation and explicit full read remain pinned");
                byte[] badBytes = "# HIT Broken\n不應啟用".getBytes(StandardCharsets.UTF_8);
                Map<String, Object> badRow = fixture.row(DocumentFixture.GUIDE, badBytes);
                badRow.put("contentDigest", "0".repeat(64));
                rejected = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, badBytes), List.of(badRow));
                fixture.publish(rejected, first, second, rejected);
                JsonNode preserved = call(client, service.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(preserved);
                assertThat(preserved.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                assertThat(preserved.path("structuredContent").path("publicationStatus").stringValue()).isEqualTo("UPDATE_REJECTED");
                assertThat(preserved.path("structuredContent").path("publicationErrorCode").stringValue()).isEqualTo("DOCUMENT_CATALOG_INVALID");
                checks.add("invalid D3 preserves actual D2 identity with explicit rejected status");
            }
            try (OwnedService invalidRestart = new OwnedService(jar, fixture, token, work.resolve("invalid-restart.log"))) {
                assertThat(invalidRestart.process.waitFor(30, TimeUnit.SECONDS)).as("Invalid startup must exit, not recover unconfigured memory").isTrue();
                assertThat(invalidRestart.process.exitValue()).isNotZero();
            }
            checks.add("restart with invalid selection fails closed");
            fixture.publish(second, first, second);
            try (OwnedService restarted = new OwnedService(jar, fixture, token, work.resolve("restarted.log"))) {
                restarted.awaitReady(client);
                initialize(client, restarted.uri, token);
                JsonNode staleCursor = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", oldCursor));
                assertError(staleCursor, "INVALID_ARGUMENT");
                JsonNode exact = call(client, restarted.uri, token, "read_document", readArguments(first, 1));
                assertSuccess(exact);
                assertThat(exact.path("structuredContent").path("content").stringValue()).isEqualTo(body);
                checks.add("valid restart restores retained exact reads but rejects old process cursor");
                fixture.publish(first, first, second);
                JsonNode firstAgain = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                assertSuccess(firstAgain);
                String pinned = firstAgain.path("structuredContent").path("page").path("nextCursor").stringValue();
                fixture.publish(second, second);
                assertError(call(client, restarted.uri, token, "read_document", readArguments(first, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertError(call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", pinned)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode remaining = call(client, restarted.uri, token, "read_document", readArguments(second, 1));
                assertSuccess(remaining);
                assertThat(remaining.path("structuredContent").path("content").stringValue()).isEqualTo("# HIT Updated\n新規則");
                checks.add("withdrawal fails explicitly without retargeting while D2 remains readable");
            }
            OwnedService interruptedOwner = new OwnedService(jar, fixture, token, work.resolve("interrupted-owner.log"));
            try {
                interruptedOwner.awaitReady(client);
                Thread.currentThread().interrupt();
                interruptedOwner.close();
                assertThat(interruptedOwner.process.isAlive()).as("Interrupted teardown still reaps its child").isFalse();
                assertThat(Files.exists(interruptedOwner.ownershipMarker)).as("Cleanup marker removed only after reaping").isFalse();
                assertThat(Thread.currentThread().isInterrupted()).as("Owner interruption is restored after reaping").isTrue();
                checks.add("interrupted owner reaps actual packaged child before restoring interruption");
            } finally {
                Thread.interrupted();
                interruptedOwner.close();
            }
        }
        Files.write(artifacts.resolve("document-native-acceptance.json"), mapper.writeValueAsBytes(Map.of(
                "status", "PASS", "documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "firstRevision", first, "secondRevision", second, "rejectedRevision", rejected,
                "checks", checks, "agentAcceptance", "NOT_EXECUTED_BY_THIS_JOURNEY")));
    }

    private Map<String, Object> readArguments(String revision, int startLine) {
        return Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY, "documentRevision", revision),
                "path", DocumentFixture.GUIDE, "sectionStartLine", startLine);
    }

    private void initialize(HttpClient client, URI uri, String token) throws Exception {
        JsonNode result = rpc(client, uri, token, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "owned-document-native-journey", "version", "1")));
        assertThat(result.path("capabilities").has("tools")).isTrue();
    }

    private JsonNode call(HttpClient client, URI uri, String token, String name, Map<String, Object> arguments) throws Exception {
        JsonNode result = rpc(client, uri, token, "tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(mapper.readTree(result.path("content").get(0).path("text").stringValue())).isEqualTo(result.path("structuredContent"));
        assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(512 * 1024);
        return result;
    }

    private void assertSuccess(JsonNode result) { assertThat(result.path("isError").booleanValue()).isFalse(); }
    private void assertError(JsonNode result, String code) {
        assertThat(result.path("isError").booleanValue()).isTrue();
        assertThat(result.path("structuredContent").path("code").stringValue()).isEqualTo(code);
    }

    private JsonNode rpc(HttpClient client, URI uri, String token, String method, Map<String, Object> parameters) throws Exception {
        HttpResponse<String> response = send(client, uri, token, method, parameters);
        assertThat(response.statusCode()).as("Actual MCP HTTP status").isEqualTo(200);
        String body = response.body();
        if (!body.stripLeading().startsWith("{")) {
            body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).strip()).findFirst().orElseThrow();
        }
        JsonNode message = mapper.readTree(body);
        assertThat(message.path("error").isMissingNode() || message.path("error").isNull()).as("No JSON-RPC protocol failure").isTrue();
        return message.path("result");
    }

    private HttpResponse<String> send(HttpClient client, URI uri, String token, String method, Map<String, Object> parameters) throws Exception {
        byte[] request = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", parameters));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri.resolve("/mcp")).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18").POST(HttpRequest.BodyPublishers.ofByteArray(request));
        if (!token.isEmpty()) { builder.header("X-Api-Token", token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static final class OwnedService implements AutoCloseable {
        private final Process process;
        private final URI uri;
        private final Path ownershipMarker;
        private OwnedService(Path jar, DocumentFixture fixture, String token, Path log) throws Exception {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            uri = URI.create("http://127.0.0.1:" + port);
            ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xms64m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-jar", jar.toString(),
                    "--server.address=127.0.0.1", "--server.port=" + port,
                    "--document.repository-id=" + DocumentFixture.DOCUMENT_REPOSITORY,
                    "--document.git-directory=" + fixture.root.resolve(".git"), "--document.publication-file=" + fixture.publication,
                    "--document.collection-purpose=TEST_ONLY");
            builder.environment().put("DOCUMENT_QUERY_API_TOKEN", token);
            ownershipMarker = log.resolveSibling(log.getFileName() + ".running");
            Files.writeString(ownershipMarker, "STARTING", StandardCharsets.UTF_8);
            process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        }
        private void awaitReady(HttpClient client) throws Exception {
            long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < until) {
                assertThat(process.isAlive()).as("Owned document service remains alive until ready").isTrue();
                try {
                    HttpResponse<Void> response = client.send(HttpRequest.newBuilder(uri.resolve("/actuator/health"))
                            .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) { return; }
                } catch (IOException exception) {
                    // The owned process has not bound its listener yet; readiness remains bounded.
                }
                Thread.sleep(100);
            }
            throw new AssertionError("Owned document service did not become ready within its startup bound");
        }
        @Override public void close() throws IOException {
            boolean interrupted = Thread.interrupted();
            try {
                process.destroy();
                boolean forced = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (process.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        assertThat(forced).as("Owned child must exit after forced termination; retain root otherwise").isFalse();
                        process.destroyForcibly();
                        forced = true;
                        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        continue;
                    }
                    try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException failure) { interrupted = true; }
                }
                Files.deleteIfExists(ownershipMarker);
            } finally {
                if (interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
