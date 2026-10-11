package com.java.semantic.document.mcp;

import com.java.semantic.document.DocumentQueryApplication;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.ReadRequest;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentOperationGate;
import com.java.semantic.document.application.DocumentQueryService;
import com.java.semantic.document.catalog.DocumentCatalog;
import com.java.semantic.document.support.DocumentFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catches an inert response timeout and premature release of timed-out worker resources. */
class DocumentMcpTimeoutTest {
    @TempDir Path temporary;

    @Test
    void statelessTimeoutRespondsWhileRealMaterializersRetainAdmissionAndPublicationLeases() throws Exception {
        Delay delay = new Delay();
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("git"));
                HttpClient client = HttpClient.newHttpClient();
                ExecutorService reloadExecutor = Executors.newSingleThreadExecutor()) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Old\nold evidence\n"));
            fixture.publish(first, first);
            ConfigurableApplicationContext application = new SpringApplicationBuilder(DocumentQueryApplication.class,
                    DelayedMaterialization.class).initializers(context -> context.getBeanFactory().registerSingleton("delay", delay))
                    .run("--server.port=0", "--document.repository-id=" + DocumentFixture.DOCUMENT_REPOSITORY,
                            "--document.git-directory=" + fixture.root.resolve(".git"),
                            "--document.publication-file=" + fixture.publication,
                            "--document.collection-purpose=TEST_ONLY", "--document.api-token=fixture-read-token");
            try {
                int port = ((WebServerApplicationContext) application).getWebServer().getPort();
                URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
                ReadRequest oldRead = new ReadRequest(new DocumentContext(DocumentFixture.DOCUMENT_REPOSITORY, first),
                        DocumentFixture.GUIDE, 1);
                Map<String, Object> readArguments = Map.of("documentContext", oldRead.documentContext(),
                        "path", DocumentFixture.GUIDE, "sectionStartLine", 1);
                long started = System.nanoTime();
                CompletableFuture<HttpResponse<String>> firstResponse = call(client, endpoint, fixture, "read_document", readArguments);
                CompletableFuture<HttpResponse<String>> secondResponse = call(client, endpoint, fixture, "read_document", readArguments);
                assertThat(delay.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertError(fixture, firstResponse.get(15, TimeUnit.SECONDS), "DOCUMENT_TIMEOUT");
                assertError(fixture, secondResponse.get(5, TimeUnit.SECONDS), "DOCUMENT_TIMEOUT");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(15));
                assertThat(delay.interrupted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(delay.release.getCount()).isEqualTo(1);
                assertError(fixture, call(client, endpoint, fixture, "read_document", readArguments).get(2, TimeUnit.SECONDS), "DOCUMENT_BUSY");
                DocumentQueryService service = application.getBean(DocumentQueryService.class);
                assertThatThrownBy(() -> service.read(oldRead, value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_BUSY));

                String second = fixture.commit(Map.of(DocumentFixture.GUIDE, "# New\nnew evidence\n"));
                fixture.publish(second, second);
                DocumentCatalog catalog = application.getBean(DocumentCatalog.class);
                CountDownLatch reloadStarted = new CountDownLatch(1);
                AtomicReference<Thread> reloadThread = new AtomicReference<>();
                Future<String> reloaded = reloadExecutor.submit(() -> {
                    reloadThread.set(Thread.currentThread());
                    reloadStarted.countDown();
                    try (DocumentCatalog.ReadLease lease = catalog.open(DocumentDeadline.after(Duration.ofSeconds(5)))) {
                        return lease.view().activeRevision();
                    }
                });
                assertThat(reloadStarted.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitState(reloadThread.get(), Thread.State.TIMED_WAITING)).isTrue();
                assertThat(reloaded.isDone()).isFalse();
                delay.release.countDown();
                assertThat(reloaded.get(5, TimeUnit.SECONDS)).isEqualTo(second);
                // Probe the actual gate to prove both permits return only after the real service unwinds.
                try (DocumentOperationGate.Permit one = awaitPermit(application.getBean(DocumentOperationGate.class));
                        DocumentOperationGate.Permit two = awaitPermit(application.getBean(DocumentOperationGate.class))) {
                    one.deadline().check();
                    two.deadline().check();
                }
                JsonNode recovered = awaitRecovery(client, endpoint, fixture);
                assertThat(recovered.path("isError").asBoolean()).isFalse();
                assertThat(recovered.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                assertThat(recovered.path("structuredContent").path("items").get(0).path("snippet").stringValue()).contains("new evidence");
            } finally {
                delay.release.countDown();
                application.close();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DelayedMaterialization {
        @Bean @Primary
        DocumentQueryFacade delayedFacade(DocumentQueryService service, DocumentResultWriter writer, Delay delay) {
            return new DocumentQueryFacade(service, writer, result -> {
                delay.entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try { delay.release.await(); break; }
                    catch (InterruptedException failure) { interrupted = true; delay.interrupted.countDown(); }
                }
                if (interrupted) { Thread.currentThread().interrupt(); }
                return writer.success(result);
            });
        }
    }

    private static JsonNode awaitRecovery(HttpClient client, URI endpoint, DocumentFixture fixture) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            JsonNode result = response(fixture, call(client, endpoint, fixture, "search_documents",
                    Map.of("query", "new evidence")).get(5, TimeUnit.SECONDS));
            JsonNode code = result.path("structuredContent").path("code");
            if (!code.isString() || !"DOCUMENT_BUSY".equals(code.stringValue())
                    || System.nanoTime() >= end) { return result; }
            TimeUnit.MILLISECONDS.sleep(1);
        }
    }

    static final class Delay {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch interrupted = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
    }

    private static CompletableFuture<HttpResponse<String>> call(HttpClient client, URI endpoint, DocumentFixture fixture,
            String tool, Map<String, Object> arguments) {
        return client.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("X-Api-Token", "fixture-read-token").header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-03-26")
                .POST(HttpRequest.BodyPublishers.ofString(fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1,
                        "method", "tools/call", "params", Map.of("name", tool, "arguments", arguments))))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode response(DocumentFixture fixture, HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
        String body = response.body();
        if (body.startsWith("event:") || body.startsWith("data:")) {
            body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).stripLeading()).findFirst().orElseThrow();
        }
        JsonNode rpc = fixture.mapper.readTree(body);
        assertThat(rpc.has("error")).isFalse();
        JsonNode result = rpc.path("result");
        assertThat(fixture.mapper.readTree(result.path("content").get(0).path("text").stringValue())).isEqualTo(result.path("structuredContent"));
        return result;
    }

    private static void assertError(DocumentFixture fixture, HttpResponse<String> response, String code) {
        JsonNode result = response(fixture, response);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").size()).isEqualTo(2);
        assertThat(result.path("structuredContent").path("code").stringValue()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(fixture.root.toString(), "fixture-read-token");
    }

    private static boolean awaitState(Thread thread, Thread.State state) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            if (thread.getState() == state) { return true; }
            if (thread.getState() == Thread.State.TERMINATED) { return false; }
            TimeUnit.MILLISECONDS.sleep(1);
        }
        return false;
    }

    private static DocumentOperationGate.Permit awaitPermit(DocumentOperationGate gate) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try { return gate.open(); }
            catch (DocumentFailure failure) {
                if (failure.code() != DocumentFailure.Code.DOCUMENT_BUSY || System.nanoTime() >= end) { throw failure; }
                TimeUnit.MILLISECONDS.sleep(1);
            }
        }
    }
}
