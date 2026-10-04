package com.java.semantic.indexer.api;

import com.java.semantic.SemanticIndexerApplication;
import com.java.semantic.indexer.source.LocalSourceFixture;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual separate JVM preparation and reopen with no Mongo, JDT or production test hook. */
class IndexerPrivateProcessIT {
    @TempDir Path root;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void source_only_process_prepares_and_reopens_while_second_writer_is_rejected() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revision = fixture.commit("source.java", "class Source {}\r\n".getBytes(StandardCharsets.UTF_8), "A");
            int port = freePort();
            Process first = launch(fixture, port, "first");
            try {
                String base = "http://127.0.0.1:" + port;
                waitForHttp(first, base);
                Process competing = launch(fixture, freePort(), "competing");
                try {
                    assertThat(competing.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(competing.exitValue()).isNotZero();
                } finally {
                    competing.destroyForcibly();
                }
                String requestId = UUID.randomUUID().toString();
                HttpResponse<String> admitted = http(base, "POST", "/index/repositories/orders/source",
                        "{\"requestId\":\"" + requestId + "\"}");
                assertThat(admitted.statusCode()).isEqualTo(202);
                JsonNode accepted = mapper.readTree(admitted.body());
                assertThat(accepted.get("requestId").asString()).isEqualTo(requestId);
                String jobId = accepted.get("jobId").asString();
                JsonNode completed = awaitComplete(first, base, requestId);
                assertThat(completed.get("resolvedRevision").asString()).isEqualTo(revision);
                assertThat(completed.get("jobId").asString()).isEqualTo(jobId);
                assertThat(Files.readAllBytes(fixture.published.resolve("orders/revisions").resolve(revision)
                        .resolve("tree/source.java"))).isEqualTo("class Source {}\r\n".getBytes(StandardCharsets.UTF_8));
                first.destroy();
                assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
                Process reopened = launch(fixture, port, "reopened");
                try {
                    waitForHttp(reopened, base);
                    JsonNode recovered = mapper.readTree(http(base, "GET",
                            "/index/repositories/orders/jobs?requestId=" + requestId, "").body());
                    assertThat(recovered.get("phase").asString()).isEqualTo("COMPLETE");
                    assertThat(recovered.get("jobId").asString()).isEqualTo(jobId);
                } finally {
                    reopened.destroyForcibly();
                    reopened.waitFor(10, TimeUnit.SECONDS);
                }
            } finally {
                first.destroyForcibly();
                first.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private Process launch(LocalSourceFixture fixture, int port, String name) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, SemanticIndexerApplication.class.getName(),
                "--server.port=" + port, "--server.address=127.0.0.1",
                "--semantic.indexer.admin-token=admin-secret",
                "--semantic.source-admin-root=" + fixture.admin,
                "--semantic.source-published-root=" + fixture.published,
                "--semantic.repositories.orders.url=" + fixture.remote.toUri(),
                "--semantic.repositories.orders.display-name=Orders",
                "--semantic.repositories.orders.default-branch=main")
                .redirectErrorStream(true).redirectOutput(root.resolve(name + ".log").toFile()).start();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitForHttp(Process process, String base) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                if (http(base, "GET", "/index/repositories/orders/jobs?requestId=" + UUID.randomUUID(), "")
                        .statusCode() == 404) {
                    return;
                }
            } catch (java.io.IOException exception) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("private source process did not become ready");
    }

    private JsonNode awaitComplete(Process process, String base, String request) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            JsonNode body = mapper.readTree(http(base, "GET", "/index/repositories/orders/jobs?requestId=" + request, "").body());
            if (body.get("phase").asString().equals("COMPLETE")) {
                return body;
            }
            assertThat(body.get("phase").asString()).isNotEqualTo("FAILED");
            Thread.sleep(100);
        }
        throw new AssertionError("private preparation did not complete");
    }

    private HttpResponse<String> http(String base, String method, String route, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(2))
                .header("X-Api-Token", "admin-secret");
        if (method.equals("POST")) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
