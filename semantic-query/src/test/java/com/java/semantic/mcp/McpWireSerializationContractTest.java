package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import tools.jackson.databind.json.JsonMapper;

class McpWireSerializationContractTest {
    @TempDir Path temp;

    @Test
    void sdk_wire_preserves_shared_invalid_argument_and_rejects_removed_tools() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        JsonMapper mapper = JsonMapper.builder().build();
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(),
                fixture.properties.maxActiveSearches());
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).mcpEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), configuration.mcpQueryToolSpecifications(facade, mapper));
        McpWireTestClient client = new McpWireTestClient(transport, mapper);
        try {
            Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA),
                    "query", "Order", "limit", "20");
            tools.jackson.databind.JsonNode result = client.call(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", 2, "method", "tools/call",
                    "params", Map.of("name", "search_text", "arguments", input)))).get("result");
            assertThat(result.get("isError").asBoolean()).isTrue();
            assertThat(result.get("structuredContent").get("code").asString()).isEqualTo("INVALID_ARGUMENT");
            assertThat(mapper.readTree(result.get("content").get(0).get("text").asString()))
                    .isEqualTo(result.get("structuredContent"));
            tools.jackson.databind.JsonNode removed = client.call(mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                    "params", Map.of("name", "search_code", "arguments", Map.of()))));
            assertThat(removed.has("error") || removed.path("result").path("isError").asBoolean()).isTrue();
            assertThat(removed.toString()).doesNotContain("Order.java", "class Order");
        } finally {
            client.delete();
            server.close();
        }
    }

}
