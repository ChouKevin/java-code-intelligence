package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.SourceAccessProperties;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
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

class McpInterruptedResponseTest {
    @TempDir Path temp;

    @Test
    void cancelled_tool_search_delivers_structured_error_over_interruptible_servlet_response() throws Exception {
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
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(),
                new LocalRepositorySourceService(properties, fixture.mapper), 1);
        JsonMapper mapper = JsonMapper.builder().build();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).mcpEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), configuration.mcpQueryToolSpecifications(facade, mapper));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            McpWireTestClient client = new McpWireTestClient(transport, mapper);
            String call = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call",
                    "params", Map.of("name", "search_text", "arguments", Map.of("context",
                            Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA),
                            "query", "Order"))));
            CompletableFuture<McpWireTestClient.Response> pending = CompletableFuture.supplyAsync(() -> {
                try {
                    return client.request(call, output -> new HttpServletResponseWrapper(output) {
                        @Override
                        public void flushBuffer() throws IOException {
                            if (Thread.currentThread().isInterrupted()) {
                                output.resetBuffer();
                                throw new IOException("Interrupted servlet response flush");
                            }
                            super.flushBuffer();
                        }
                    });
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            }, executor);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(Files.exists(pidFile)).isTrue();
                ProcessHandle child = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
                assertThat(child.isAlive()).isTrue();
                String cancel = mapper.writeValueAsString(Map.of("jsonrpc", "2.0",
                        "method", "notifications/cancelled", "params", Map.of("requestId", 7,
                                "reason", "client cancelled")));
                client.request(cancel);
                McpWireTestClient.Response response = pending.get(3, TimeUnit.SECONDS);
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.body()).describedAs("Cancelled tools/call SSE wire=%s", response.body())
                        .contains("data:");
                JsonNode result = mapper.readTree(McpWireTestClient.jsonBody(response.body())).get("result");
                assertThat(result).describedAs("Cancelled tools/call response; wire=%s", response.body()).isNotNull();
                assertThat(result.get("isError").asBoolean()).isTrue();
                assertThat(result.get("structuredContent").get("code").asString()).isEqualTo("SOURCE_TIMEOUT");
                assertThat(child.isAlive()).isFalse();
                Files.writeString(released, "ready");
                assertThat(client.call(call).get("result").get("isError").asBoolean()).isFalse();
                client.delete();
            } finally {
                pending.cancel(true);
            }
        } finally {
            server.close();
        }
    }
}
