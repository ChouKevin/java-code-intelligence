package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.model.source.SourceReadContract.FileFindRequest;
import com.java.semantic.model.source.SourceReadContract.FileFindResult;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.TextSearchRequest;
import com.java.semantic.model.source.SourceReadContract.TextSearchResult;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import tools.jackson.databind.json.JsonMapper;

class SourceMcpCancellationTest {
    @TempDir Path temp;

    @Test
    void sdk_cancelled_notification_interrupts_real_inflight_search_reaps_child_and_releases_only_slot() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        Path pidFile = temp.resolve("rg.pid");
        Path complete = temp.resolve("released");
        Path executable = temp.resolve("slow-rg");
        Files.writeString(executable, "#!/bin/sh\nif test -f '" + complete + "'; then exit 1; fi\necho $$ > '" + pidFile
                + "'\nexec /bin/sleep 30\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        SourceAccessProperties properties = new SourceAccessProperties(fixture.root, executable, List.of("sample"), 65_536,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 1);
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(),
                new LocalRepositorySourceService(properties, fixture.mapper), fixture.index, properties.maxActiveSearches());
        JsonMapper mapper = JsonMapper.builder().build();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).mcpEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), configuration.mcpQueryToolSpecifications(facade, mapper));
        McpWireTestClient client = new McpWireTestClient(transport, mapper);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample",
                    "revision", SourceFilesystemFixture.SHA), "query", "Order");
            String call = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "long-search", "method", "tools/call",
                    "params", Map.of("name", "search_text", "arguments", input)));
            long started = System.nanoTime();
            CompletableFuture<McpWireTestClient.Response> pending = CompletableFuture.supplyAsync(() -> {
                try { return client.request(call); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }, executor);
            try {
                while (!Files.exists(pidFile) && elapsed(started) < 2_000) Thread.sleep(10);
                assertThat(Files.exists(pidFile)).isTrue();
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                ProcessHandle child = ProcessHandle.of(pid).orElseThrow();
                assertThat(child.isAlive()).isTrue();
                String cancel = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                        "params", Map.of("requestId", "long-search", "reason", "client cancelled")));
                client.request(cancel);
                McpWireTestClient.Response result = pending.get(2, TimeUnit.SECONDS);
                assertThat(mapper.readTree(McpWireTestClient.jsonBody(result.body()))
                        .get("result").get("isError").asBoolean()).isTrue();
                assertThat(child.isAlive()).isFalse();
                assertThat(elapsed(started)).isLessThan(4_000);
                client.request(cancel);
                String malformed = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method",
                        "notifications/cancelled", "params", Map.of()));
                client.request(malformed);
                Files.writeString(complete, "ready");
                CompletableFuture<McpWireTestClient.Response> following = CompletableFuture.supplyAsync(() -> {
                    try {
                        return client.request(mapper.writeValueAsString(Map.of(
                                "jsonrpc", "2.0", "id", "second-search", "method", "tools/call",
                                "params", Map.of("name", "search_text", "arguments", input))));
                    } catch (Exception exception) { throw new IllegalStateException(exception); }
                }, executor);
                assertThat(mapper.readTree(McpWireTestClient.jsonBody(following.get(2, TimeUnit.SECONDS).body()))
                        .get("result").get("isError").asBoolean()).isFalse();
            } finally {
                pending.cancel(true);
            }
        } finally {
            client.delete();
            server.close();
        }
    }

    @Test
    void sdk_cancelled_discovery_releases_its_source_guard_and_shared_search_slot() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        RepositorySourcePort delegate = fixture.service();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        RepositorySourcePort held = new RepositorySourcePort() {
            public FileCollection listFiles(AdmittedSourceRevision admitted, FileListRequest request) { return delegate.listFiles(admitted, request); }
            public TextSearchResult searchText(AdmittedSourceRevision admitted, TextSearchRequest request) { return delegate.searchText(admitted, request); }
            public SourceResult readSource(AdmittedSourceRevision admitted, ReadSourceRequest request,
                    Function<Optional<String>, IndexContract.FoldResult> folds) { return delegate.readSource(admitted, request, folds); }
            public FileFindResult findFiles(AdmittedSourceRevision admitted, FileFindRequest request) {
                if (first.getAndSet(false)) {
                    entered.countDown();
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT, exception);
                    }
                }
                return delegate.findFiles(admitted, request);
            }
        };
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), held, fixture.index, 1);
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(fixture.mapper)).mcpEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport), new McpServerProperties(),
                configuration.mcpQueryToolSpecifications(facade, fixture.mapper));
        McpWireTestClient client = new McpWireTestClient(transport, fixture.mapper);
        try (ExecutorService executor = Executors.newSingleThreadExecutor();
                FileChannel exclusive = FileChannel.open(fixture.root.resolve("sample/read.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            Map<String, Object> input = Map.of("context", fixture.context, "filePattern", "*.java");
            CompletableFuture<McpWireTestClient.Response> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return client.request(fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "held-find",
                            "method", "tools/call", "params", Map.of("name", "find_files", "arguments", input))));
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            }, executor);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(exclusive::tryLock).isInstanceOf(OverlappingFileLockException.class);
                McpWireTestClient.Response busy = client.request(fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "busy-find",
                        "method", "tools/call", "params", Map.of("name", "find_files", "arguments", input))));
                assertThat(fixture.mapper.readTree(McpWireTestClient.jsonBody(busy.body())).get("result")
                        .get("structuredContent").get("code").asString()).isEqualTo("SOURCE_BUSY");
                client.request(fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                        "params", Map.of("requestId", "held-find", "reason", "cancel discovery"))));
                JsonNode cancelled = fixture.mapper.readTree(McpWireTestClient.jsonBody(pending.get(2, TimeUnit.SECONDS).body()));
                assertThat(cancelled.get("result").get("isError").asBoolean()).isTrue();
                assertThat(cancelled.get("result").get("structuredContent").get("code").asString()).isEqualTo("SOURCE_TIMEOUT");
                try (FileLock available = exclusive.tryLock()) { assertThat(available).isNotNull(); }
                McpWireTestClient.Response following = client.request(fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", "next-find",
                        "method", "tools/call", "params", Map.of("name", "find_files", "arguments", input))));
                JsonNode result = fixture.mapper.readTree(McpWireTestClient.jsonBody(following.body())).get("result");
                assertThat(result.get("isError").asBoolean()).isFalse();
                assertThat(result.get("structuredContent").get("items").get(0).get("path").asString()).isEqualTo("Order.java");
            } finally { release.countDown(); pending.cancel(true); }
        } finally { client.delete(); server.close(); }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
