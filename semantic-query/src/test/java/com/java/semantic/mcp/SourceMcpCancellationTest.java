package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

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
                new LocalRepositorySourceService(properties, fixture.mapper), properties.maxActiveSearches());
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

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
