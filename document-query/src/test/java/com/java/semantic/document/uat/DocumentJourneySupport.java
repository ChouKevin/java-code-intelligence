package com.java.semantic.document.uat;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared real-wire client and interruption-safe owned child teardown. */
abstract class DocumentJourneySupport {
    final JsonMapper mapper = JsonMapper.builder().build();
    private int requestId;

    Map<String, Object> readArguments(String revision, int startLine) {
        return readArguments("sample", revision, DocumentFixture.GUIDE, startLine);
    }

    Map<String, Object> readArguments(String owner, String revision, String path, int startLine) {
        return Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "repositoryId", owner, "documentRevision", revision), "path", path, "sectionStartLine", startLine);
    }

    void initialize(HttpClient client, URI uri, String token) throws Exception {
        JsonNode result = rpc(client, uri, token, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "owned-document-native-journey", "version", "1")));
        assertThat(result.path("capabilities").has("tools")).isTrue();
    }

    JsonNode call(HttpClient client, URI uri, String token, String name, Map<String, Object> arguments) throws Exception {
        JsonNode result = rpc(client, uri, token, "tools/call", Map.of("name", name, "arguments", arguments));
        assertThat(mapper.readTree(result.path("content").get(0).path("text").stringValue())).isEqualTo(result.path("structuredContent"));
        assertThat(mapper.writeValueAsBytes(result).length).isLessThanOrEqualTo(512 * 1024);
        return result;
    }

    void assertSuccess(JsonNode result) { assertThat(result.path("isError").booleanValue()).isFalse(); }
    void assertError(JsonNode result, String code) {
        assertThat(result.path("isError").booleanValue()).isTrue();
        assertThat(result.path("structuredContent").path("code").stringValue()).isEqualTo(code);
    }

    JsonNode rpc(HttpClient client, URI uri, String token, String method, Map<String, Object> parameters) throws Exception {
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

    HttpResponse<String> send(HttpClient client, URI uri, String token, String method, Map<String, Object> parameters) throws Exception {
        byte[] request = mapper.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", ++requestId, "method", method, "params", parameters));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri.resolve("/mcp")).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18").POST(HttpRequest.BodyPublishers.ofByteArray(request));
        if (!token.isEmpty()) { builder.header("X-Api-Token", token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static final class OwnedService implements AutoCloseable {
        final Process process;
        final URI uri;
        final Path ownershipMarker;
        OwnedService(Path jar, DocumentFixture fixture, String token, Path log) throws Exception {
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
        void awaitReady(HttpClient client) throws Exception {
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
        @Override public void close() throws IOException { reap(process, ownershipMarker); }
    }

    static void reap(Process process, Path ownershipMarker) throws IOException {
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
