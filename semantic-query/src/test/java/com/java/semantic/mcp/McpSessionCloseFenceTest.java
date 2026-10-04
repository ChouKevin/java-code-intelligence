package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.TextSearchRequest;
import com.java.semantic.model.source.SourceReadContract.TextSearchResult;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.SourceAccessProperties;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import tools.jackson.databind.json.JsonMapper;

class McpSessionCloseFenceTest {
    @TempDir Path temp;

    @Test
    void delete_waits_until_owned_search_cleanup_finishes_before_releasing_session() throws Exception {
        verifyCloseWaitsForCleanup(false, false);
    }

    @Test
    void graceful_server_close_waits_until_owned_search_cleanup_finishes() throws Exception {
        verifyCloseWaitsForCleanup(true, false);
    }

    @Test
    void graceful_server_close_fails_within_one_budget_when_cleanup_cannot_finish() throws Exception {
        verifyCloseWaitsForCleanup(true, true);
    }

    private void verifyCloseWaitsForCleanup(boolean serverShutdown, boolean holdPastDeadline) throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        Path pidFile = temp.resolve("rg.pid");
        Path released = temp.resolve("released");
        Path executable = temp.resolve("controlled-rg");
        Files.writeString(executable, "#!/bin/sh\nif test -f '" + released + "'; then exit 1; fi\necho $$ > '"
                + pidFile + "'\nexec /bin/sleep 30\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        SourceAccessProperties properties = new SourceAccessProperties(fixture.root, executable, List.of("sample"),
                65_536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 1);
        RepositorySourcePort source = new LocalRepositorySourceService(properties, fixture.mapper);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CompletableFuture<Void> releaseCleanup = new CompletableFuture<>();
        RepositorySourcePort delayedCleanup = new RepositorySourcePort() {
            @Override
            public FileCollection listFiles(AdmittedSourceRevision revision, FileListRequest request) {
                return source.listFiles(revision, request);
            }

            @Override
            public TextSearchResult searchText(AdmittedSourceRevision revision, TextSearchRequest request) {
                try { return source.searchText(revision, request); }
                finally {
                    // The real subprocess has been reaped, but the facade still owns its search permit.
                    cleanupEntered.countDown();
                    releaseCleanup.join();
                }
            }

            @Override
            public SourceResult readSource(AdmittedSourceRevision revision, ReadSourceRequest request) {
                return source.readSource(revision, request);
            }
        };
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), delayedCleanup, 1);
        JsonMapper mapper = JsonMapper.builder().build();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).mcpEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), configuration.mcpQueryToolSpecifications(facade, mapper));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            McpWireTestClient owner = new McpWireTestClient(transport, mapper);
            String call = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
                    "params", Map.of("name", "search_text", "arguments", Map.of("context",
                            Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA),
                            "query", "Order"))));
            CompletableFuture<McpWireTestClient.Response> pending = CompletableFuture.supplyAsync(() -> {
                try { return owner.request(call); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }, executor);
            CompletableFuture<Void> closing = null;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(Files.exists(pidFile)).isTrue();
                ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
                assertThat(child.isAlive()).isTrue();
                closing = CompletableFuture.runAsync(() -> {
                    try {
                        if (serverShutdown) server.closeGracefully();
                        else owner.delete();
                    } catch (Exception exception) { throw new IllegalStateException(exception); }
                }, executor);
                assertThat(cleanupEntered.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(child.isAlive()).isFalse();
                assertThat(closing).isNotDone();
                assertThat(pending).isNotDone();
                if (holdPastDeadline) {
                    CompletableFuture<Void> closingAttempt = closing;
                    assertThatThrownBy(() -> closingAttempt.get(9, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class)
                            .hasRootCauseInstanceOf(TimeoutException.class);
                    assertThat(pending).isNotDone();
                    return;
                }
                releaseCleanup.complete(null);
                closing.get(3, TimeUnit.SECONDS);
                assertThat(child.isAlive()).isFalse();
                assertThat(mapper.readTree(McpWireTestClient.jsonBody(pending.get(3, TimeUnit.SECONDS).body()))
                        .get("result").get("isError").asBoolean()).isTrue();
                if (!serverShutdown) {
                    Files.writeString(released, "ready");
                    McpWireTestClient other = new McpWireTestClient(transport, mapper);
                    assertThat(other.call(call).get("result").get("isError").asBoolean()).isFalse();
                    other.delete();
                }
            } finally {
                releaseCleanup.complete(null);
                pending.cancel(true);
                if (closing != null) closing.cancel(true); // cs-allow
            }
        } finally {
            server.close();
        }
    }
}
