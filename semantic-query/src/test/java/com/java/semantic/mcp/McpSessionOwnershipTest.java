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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class McpSessionOwnershipTest {
    @TempDir Path temp;

    @Test
    void independent_initialized_clients_can_reuse_id_without_foreign_cancel_and_owner_cancel_reaps_child() throws Exception {
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
                65_536, Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(),
                new LocalRepositorySourceService(properties, fixture.mapper), properties.maxActiveSearches());
        JsonMapper mapper = JsonMapper.builder().build();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).mcpEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), configuration.mcpQueryToolSpecifications(facade, mapper));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            McpWireTestClient a = new McpWireTestClient(transport, mapper);
            McpWireTestClient b = new McpWireTestClient(transport, mapper);
            assertThat(a.session()).isNotEqualTo(b.session());
            Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample",
                    "revision", SourceFilesystemFixture.SHA), "query", "Order");
            String call = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
                    "params", Map.of("name", "search_text", "arguments", input)));
            CompletableFuture<McpWireTestClient.Response> pending = CompletableFuture.supplyAsync(() -> {
                try { return a.request(call); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }, executor);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(Files.exists(pidFile)).isTrue();
                ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
                assertThat(child.isAlive()).isTrue();
                Files.writeString(released, "ready");
                McpWireTestClient.Response bResponse = b.request(call);
                JsonNode bResult = result(mapper, bResponse);
                assertThat(bResult).describedAs("B tools/call result; wire=%s", bResponse.body()).isNotNull();
                assertThat(bResult.get("isError").asBoolean()).isFalse();
                String cancel = mapper.writeValueAsString(Map.of("jsonrpc", "2.0",
                        "method", "notifications/cancelled", "params", Map.of("requestId", 7,
                                "reason", "client cancelled")));
                b.request(cancel);
                assertThat(child.isAlive()).isTrue();
                assertThat(pending.isDone()).isFalse();
                a.request(cancel);
                assertThat(result(mapper, pending.get(3, TimeUnit.SECONDS)).get("isError").asBoolean()).isTrue();
                assertThat(child.isAlive()).isFalse();
                assertThat(result(mapper, b.request(call)).get("isError").asBoolean()).isFalse();
                Files.delete(released);
                Files.delete(pidFile);
                CompletableFuture<McpWireTestClient.Response> closing = CompletableFuture.supplyAsync(() -> {
                    try { return a.request(call); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                }, executor);
                try {
                    long closingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!Files.exists(pidFile) && System.nanoTime() < closingDeadline) Thread.sleep(10);
                    assertThat(Files.exists(pidFile)).isTrue();
                    ProcessHandle closingChild = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()))
                            .orElseThrow();
                    assertThat(closingChild.isAlive()).isTrue();
                    a.delete();
                    assertThat(result(mapper, closing.get(3, TimeUnit.SECONDS)).get("isError").asBoolean()).isTrue();
                    assertThat(closingChild.isAlive()).isFalse();
                    Files.writeString(released, "ready");
                    assertThat(result(mapper, b.request(call)).get("isError").asBoolean()).isFalse();
                } finally {
                    closing.cancel(true);
                }
                b.delete();
                assertThat(McpWireTestClient.request(transport, "POST", call, a.session()).status()).isEqualTo(404);
            } finally {
                pending.cancel(true);
            }
        } finally {
            server.close();
        }
    }

    private static JsonNode result(JsonMapper mapper, McpWireTestClient.Response response) throws Exception {
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(McpWireTestClient.jsonBody(response.body())).get("result");
    }
}
