package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.mcp.CancellableMcpTransport;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

class McpWireSerializationContractTest {
    @TempDir Path temp;

    @Test
    void sdk_wire_preserves_shared_invalid_argument_and_rejects_removed_tools() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        JsonMapper mapper = JsonMapper.builder().build();
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service());
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpServerProperties properties = new McpServerProperties();
        McpStatelessSyncServer server = configuration.queryMcpServer(new CancellableMcpTransport(transport), properties,
                configuration.mcpQueryToolSpecifications(facade, mapper));
        try {
            Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA),
                    "query", "Order", "limit", "20");
            tools.jackson.databind.JsonNode result = mapper.readTree(request(transport, mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", "search_text", "arguments", input))))).get("result");
            assertThat(result.get("isError").asBoolean()).isTrue();
            assertThat(result.get("structuredContent").get("code").asString()).isEqualTo("INVALID_ARGUMENT");
            assertThat(mapper.readTree(result.get("content").get(0).get("text").asString()))
                    .isEqualTo(result.get("structuredContent"));
            tools.jackson.databind.JsonNode removed = mapper.readTree(request(transport, mapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0", "id", 2, "method", "tools/call",
                    "params", Map.of("name", "search_code", "arguments", Map.of())))));
            assertThat(removed.has("error") || removed.path("result").path("isError").asBoolean()).isTrue();
            assertThat(removed.toString()).doesNotContain("Order.java", "class Order");
        } finally {
            server.close();
        }
    }

    static String request(WebMvcStatelessServerTransport transport, String body) throws Exception {
        MockHttpServletRequest servlet = new MockHttpServletRequest("POST", "/mcp");
        servlet.setContentType(MediaType.APPLICATION_JSON_VALUE);
        servlet.addHeader("Accept", "application/json, text/event-stream");
        servlet.setContent(body.getBytes(StandardCharsets.UTF_8));
        List<HttpMessageConverter<?>> converters = List.of(new StringHttpMessageConverter());
        ServerRequest request = ServerRequest.create(servlet, converters);
        ServerResponse response = transport.getRouterFunction().route(request).orElseThrow().handle(request);
        MockHttpServletResponse output = new MockHttpServletResponse();
        response.writeTo(servlet, output, () -> converters);
        return output.getContentAsString();
    }
}
