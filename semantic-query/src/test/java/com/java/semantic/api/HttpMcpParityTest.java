package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.QuerySecurityProperties;
import com.java.semantic.api.QueryTokenFilter;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.SemanticMcpToolCatalog;
import com.java.semantic.model.source.SourceReadContract.ContextRequest;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpMcpParityTest {
    @TempDir Path temp;

    @Test
    void five_source_operations_and_safe_failure_categories_have_identical_http_and_mcp_results() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("src/Order.java", "class Order { String name = \"訂單\"; }\n");
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service());
        QuerySecurityProperties security = new QuerySecurityProperties();
        security.setApiToken("query-token");
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper))
                .addFilters(new QueryTokenFilter(security)).build();
        List<McpStatelessServerFeatures.SyncToolSpecification> tools = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, fixture.mapper);
        assertThat(SemanticMcpToolCatalog.tools()).extracting(SemanticMcpToolCatalog.ToolDefinition::name)
                .containsExactly("list_repositories", "get_context", "list_files", "search_text", "read_source");
        Map<String, Object> context = Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA);
        assertParity(http, tools, fixture.mapper, "list_repositories", "/api/v1/repositories", Map.of(), false, 200, null);
        assertParity(http, tools, fixture.mapper, "get_context", "/api/v1/context", Map.of("repositoryId", "sample"), false, 200, null);
        assertParity(http, tools, fixture.mapper, "list_files", "/api/v1/files", Map.of("context", context), false, 200, null);
        assertParity(http, tools, fixture.mapper, "search_text", "/api/v1/search-text",
                Map.of("context", context, "query", "Order"), false, 200, null);
        assertParity(http, tools, fixture.mapper, "read_source", "/api/v1/source",
                Map.of("context", context, "path", "src/Order.java"), false, 200, null);
        assertParity(http, tools, fixture.mapper, "get_context", "/api/v1/context",
                Map.of("repositoryId", "other"), true, 404, "REPOSITORY_NOT_FOUND");
        assertParity(http, tools, fixture.mapper, "get_context", "/api/v1/context",
                Map.of("repositoryId", "sample", "revision", "f".repeat(40)), true, 404, "REVISION_NOT_PREPARED");
        assertParity(http, tools, fixture.mapper, "read_source", "/api/v1/source",
                Map.of("context", context, "path", "missing.java"), true, 404, "SOURCE_NOT_FOUND");
        assertParity(http, tools, fixture.mapper, "read_source", "/api/v1/source",
                Map.of("context", Map.of("repositoryId", "sample", "revision", "main"), "path", "src/Order.java"),
                true, 400, "INVALID_ARGUMENT");
        assertThat(facade.getContext(new ContextRequest("sample", Optional.empty())).context()).contains(fixture.context);
    }

    private static void assertParity(MockMvc http, List<McpStatelessServerFeatures.SyncToolSpecification> tools,
            tools.jackson.databind.ObjectMapper mapper, String operation, String path, Map<String, Object> input,
            boolean expectedError, int expectedStatus, String expectedCode) throws Exception {
        String payload = (operation.equals("list_repositories")
                ? http.perform(get(path).header(QueryTokenFilter.TOKEN_HEADER, "query-token"))
                : http.perform(post(path).header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content(mapper.writeValueAsString(input))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        McpStatelessServerFeatures.SyncToolSpecification tool = tools.stream()
                .filter(value -> value.tool().name().equals(operation)).findFirst().orElseThrow();
        McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                McpSchema.CallToolRequest.builder(operation).arguments(input).build());
        JsonNode httpJson = mapper.readTree(payload);
        assertThat(httpJson).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcp.structuredContent())));
        assertThat(mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text())).isEqualTo(httpJson);
        assertThat(mcp.isError()).isEqualTo(expectedError);
        if (expectedCode != null) assertThat(httpJson.get("code").asText()).isEqualTo(expectedCode); // cs-allow
        assertThat(payload).doesNotContain("/published/", "password", "source-admin");
    }
}
