package com.java.semantic.mcp;

import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpWireSerializationContractTest {
    @Test
    void native_sdk_does_not_replace_shared_invalid_argument_with_unstructured_schema_errors() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();
        SemanticQueryFacade facade = com.java.semantic.api.HttpMcpParityTest.facade(mock(MongoTemplate.class));
        WebMvcStatelessServerTransport transport = WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper)).messageEndpoint("/mcp").build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        McpServerProperties properties = new McpServerProperties();
        McpStatelessSyncServer server = configuration.queryMcpServer(transport, properties,
                configuration.mcpQueryToolSpecifications(facade, mapper));
        try {
            Map<String, Object> input = Map.of("context", Map.of("kind", "CURRENT", "repositoryId", "orders", "revision", "a".repeat(40)),
                    "query", "Order", "limit", "20");
            String wire = request(transport, mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", "search_code", "arguments", input))));
            tools.jackson.databind.JsonNode result = mapper.readTree(wire).get("result");
            assertThat(result.get("isError").asBoolean()).isTrue();
            assertThat(result.get("structuredContent").get("code").asText()).isEqualTo("INVALID_ARGUMENT");
            assertThat(mapper.readTree(result.get("content").get(0).get("text").asText())).isEqualTo(result.get("structuredContent"));
        } finally {
            server.close();
        }
    }

    private static String request(WebMvcStatelessServerTransport transport, String body) throws Exception {
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
