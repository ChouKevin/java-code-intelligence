package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileEntry;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

class SourceDirectoryResponseBudgetTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void long_inventory_paths_page_before_complete_mcp_wire_overflow_without_losing_entries(int idKind) throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String directory = String.join("/", Collections.nCopies(32, "a".repeat(80) + "中😀\""));
        fixture.directory(directory);
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String path = directory + "/file-%03d.java".formatted(index);
            fixture.file(path, "class Example {}\n");
            expected.add(path);
        }
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index,
                fixture.properties.maxActiveSearches());
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<McpServerFeatures.SyncToolSpecification> tools = configuration.mcpQueryToolSpecifications(facade, fixture.mapper);
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(fixture.mapper)).mcpEndpoint("/mcp").build();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), tools);
        McpWireTestClient client = new McpWireTestClient(transport, fixture.mapper);
        try {
            Map<String, Object> context = Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA);
            Optional<String> cursor = Optional.empty();
            List<String> actual = new ArrayList<>();
            int pages = 0;
            Object requestId = switch (idKind) {
                case 0 -> 2;
                case 1 -> "x".repeat(510);
                default -> "\u0001".repeat(83) + "中😀abcde";
            };
            if (requestId instanceof String) {
                assertThat(fixture.mapper.writeValueAsBytes(requestId).length).isEqualTo(512);
            }
            do {
                Map<String, Object> request = new HashMap<>(Map.of("context", context, "directory", directory, "depth", 1, "limit", 100));
                cursor.ifPresent(value -> request.put("cursor", value));
                String body = http.perform(post("/api/v1/files").contentType("application/json")
                        .content(fixture.mapper.writeValueAsBytes(request))).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                McpWireTestClient.Response response = client.request(fixture.mapper.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "id", requestId, "method", "tools/call",
                        "params", Map.of("name", "list_files", "arguments", request))));
                assertThat(response.status()).isEqualTo(200);
                String wire = McpWireTestClient.jsonBody(response.body());
                assertThat(wire.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(524_288);
                JsonNode envelope = fixture.mapper.readTree(wire);
                assertThat(envelope.get("id")).isEqualTo(fixture.mapper.valueToTree(requestId));
                JsonNode result = envelope.get("result");
                assertThat(result.get("isError").asBoolean()).isFalse();
                JsonNode httpJson = fixture.mapper.readTree(body);
                assertThat(result.get("structuredContent")).isEqualTo(httpJson);
                assertThat(fixture.mapper.readTree(result.get("content").get(0).get("text").asString())).isEqualTo(httpJson);
                FileCollection page = fixture.mapper.treeToValue(httpJson, FileCollection.class);
                actual.addAll(page.items().stream().map(FileEntry::path).toList());
                cursor = page.page().nextCursor();
                pages++;
                assertThat(pages).isLessThan(10);
            } while (cursor.isPresent());
            assertThat(pages).isGreaterThan(1);
            assertThat(actual).containsExactlyElementsOf(expected);
        } finally {
            client.delete();
            server.close();
        }
    }

    @Test
    void oversized_ids_receive_bounded_http400_without_source_execution_and_session_remains_usable() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("src/Example.java", "class Example {}\n");
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index,
                fixture.properties.maxActiveSearches());
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        AtomicInteger toolExecutions = new AtomicInteger();
        List<McpServerFeatures.SyncToolSpecification> tools = configuration.mcpQueryToolSpecifications(facade, fixture.mapper)
                .stream().map(specification -> McpServerFeatures.SyncToolSpecification.builder()
                        .tool(specification.tool()).callHandler((context, request) -> {
                            toolExecutions.incrementAndGet();
                            return specification.callHandler().apply(context, request);
                        }).build()).toList();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(fixture.mapper)).mcpEndpoint("/mcp").build();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), tools);
        McpWireTestClient client = new McpWireTestClient(transport, fixture.mapper);
        try {
            Map<String, Object> arguments = Map.of("context",
                    Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA),
                    "directory", "src", "depth", 1);
            Map<String, Object> params = Map.of("name", "list_files", "arguments", arguments);
            List<String> oversizedIds = List.of("x".repeat(400_000), "x".repeat(511),
                    "\u0001".repeat(83) + "中😀abcdef");
            for (String id : oversizedIds) {
                assertThat(fixture.mapper.writeValueAsBytes(id).length).isGreaterThan(512);
                McpWireTestClient.Response initialize = McpWireTestClient.request(transport, fixture.mapper, "POST",
                        fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
                                "method", "initialize", "params", Map.of(
                                        "protocolVersion", "2025-06-18", "capabilities", Map.of(),
                                        "clientInfo", Map.of("name", "test-client", "version", "1")))), null);
                assertThat(initialize.status()).isEqualTo(400);
                assertThat(initialize.session()).isNull();
                assertThat(initialize.body().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
                assertThat(initialize.body()).doesNotContain(id).doesNotContain("data:").doesNotContain("jsonrpc");
                assertThat(toolExecutions.get()).isZero();
                for (String method : List.of("tools/call", "tools/list")) {
                    McpWireTestClient.Response response = client.request(fixture.mapper.writeValueAsString(Map.of(
                            "jsonrpc", "2.0", "id", id, "method", method, "params", params)));
                    assertThat(response.status()).isEqualTo(400);
                    assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
                    assertThat(response.body()).doesNotContain(id).doesNotContain("data:").doesNotContain("jsonrpc");
                    assertThat(toolExecutions.get()).isZero();
                }
            }
            JsonNode valid = client.call(fixture.mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", "still-usable", "method", "tools/call", "params", params)));
            assertThat(valid.get("id").asString()).isEqualTo("still-usable");
            JsonNode result = valid.get("result");
            assertThat(result.get("isError").asBoolean()).isFalse();
            FileCollection page = fixture.mapper.treeToValue(result.get("structuredContent"), FileCollection.class);
            assertThat(page.items()).extracting(FileEntry::path).containsExactly("src/Example.java");
            assertThat(toolExecutions.get()).isEqualTo(1);
        } finally {
            client.delete();
            server.close();
        }
    }
}
