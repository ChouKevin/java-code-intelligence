package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

class SourceReadResponseBudgetTest {
    @TempDir Path temp;

    @Test
    void dense_json_escaping_stays_within_both_wire_budgets_and_continuation_reconstructs_original_bytes() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String content = "\u0001\\\"\t中😀".repeat(40_000) + "\r\n";
        fixture.file("src/字 \"quoted\".java", content);
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(),
                fixture.properties.maxActiveSearches());
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        List<McpServerFeatures.SyncToolSpecification> tools = configuration.mcpQueryToolSpecifications(
                facade, fixture.mapper);
        McpServerFeatures.SyncToolSpecification tool = tools.stream()
                .filter(value -> value.tool().name().equals("read_source")).findFirst().orElseThrow();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(fixture.mapper)).mcpEndpoint("/mcp").build();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport),
                new McpServerProperties(), tools);
        McpWireTestClient client = new McpWireTestClient(transport, fixture.mapper);
        try {
        Map<String, Object> context = Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA);
        StringBuilder reconstructed = new StringBuilder(content.length());
        Optional<String> cursor = Optional.empty();
        int pages = 0;
        do {
            Map<String, Object> request = new HashMap<>(Map.of("context", context, "path", "src/字 \"quoted\".java"));
            cursor.ifPresent(value -> request.put("cursor", value));
            String body = http.perform(post("/api/v1/source").contentType("application/json")
                    .content(fixture.mapper.writeValueAsBytes(request))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                    McpSchema.CallToolRequest.builder("read_source").arguments(request).build());
            JsonNode httpJson = fixture.mapper.readTree(body);
            assertThat(httpJson).isEqualTo(fixture.mapper.readTree(fixture.mapper.writeValueAsString(mcp.structuredContent())));
            assertThat(fixture.mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text())).isEqualTo(httpJson);
            assertThat(mcp.isError()).isFalse();
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(524_288);
            assertThat(fixture.mapper.writeValueAsBytes(mcp).length).isLessThanOrEqualTo(524_288);
            McpWireTestClient.Response response = client.request(fixture.mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", pages + 2, "method", "tools/call",
                    "params", Map.of("name", "read_source", "arguments", request))));
            String wire = McpWireTestClient.jsonBody(response.body());
            assertThat(wire.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(524_288);
            JsonNode nativeResult = fixture.mapper.readTree(wire).get("result");
            assertThat(nativeResult.get("structuredContent")).isEqualTo(httpJson);
            assertThat(fixture.mapper.readTree(nativeResult.get("content").get(0).get("text").asString()))
                    .isEqualTo(httpJson);
            SourceResult page = fixture.mapper.treeToValue(httpJson, SourceResult.class);
            reconstructed.append(page.content());
            cursor = page.nextCursor();
            pages++;
            assertThat(pages).isLessThan(100);
        } while (cursor.isPresent());
        assertThat(pages).isGreaterThan(1);
        assertThat(reconstructed.toString().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            client.delete();
            server.close();
        }
    }
}
