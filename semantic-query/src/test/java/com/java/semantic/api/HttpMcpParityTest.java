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
import io.modelcontextprotocol.server.McpServerFeatures;
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

class HttpMcpParityTest {
    @TempDir Path temp;

    @Test
    void source_and_index_operations_share_transport_results_and_safe_failure_categories() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.directory("src");
        fixture.file("src/Order.java", "class Order { String name = \"訂單\"; }\n");
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index,
                fixture.properties.maxActiveSearches());
        QuerySecurityProperties security = new QuerySecurityProperties();
        security.setApiToken("query-token");
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper))
                .addFilters(new QueryTokenFilter(security)).build();
        List<McpServerFeatures.SyncToolSpecification> tools = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, fixture.mapper);
        assertThat(SemanticMcpToolCatalog.tools()).extracting(SemanticMcpToolCatalog.ToolDefinition::name)
                .containsExactly("list_repositories", "get_context", "list_files", "search_text", "read_source",
                        "search_code", "get_outline", "list_entry_points", "find_relations");
        Map<String, Object> context = Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA);
        assertParity(http, tools, fixture.mapper, "list_repositories", "/api/v1/repositories", Map.of(), false, 200, null);
        assertParity(http, tools, fixture.mapper, "get_context", "/api/v1/context", Map.of("repositoryId", "sample"), false, 200, null);
        assertParity(http, tools, fixture.mapper, "list_files", "/api/v1/files", Map.of("context", context), false, 200, null);
        assertParity(http, tools, fixture.mapper, "search_text", "/api/v1/search-text",
                Map.of("context", context, "query", "Order"), false, 200, null);
        assertParity(http, tools, fixture.mapper, "read_source", "/api/v1/source",
                Map.of("context", context, "path", "src/Order.java"), false, 200, null);
        assertParity(http, tools, fixture.mapper, "search_code", "/api/v1/search-code",
                Map.of("context", context, "query", "Order", "match", "prefix"), true, 409, "INDEX_NOT_PREPARED");
        assertParity(http, tools, fixture.mapper, "get_outline", "/api/v1/outline",
                Map.of("context", context, "path", "src/Order.java"), true, 409, "INDEX_NOT_PREPARED");
        assertParity(http, tools, fixture.mapper, "list_entry_points", "/api/v1/entry-points",
                Map.of("context", context), true, 409, "INDEX_NOT_PREPARED");
        assertParity(http, tools, fixture.mapper, "find_relations", "/api/v1/relations",
                Map.of("context", context, "indexGeneration", "00000000-0000-0000-0000-000000000001",
                        "anchor", Map.of("kind", "FILE", "path", "src/Order.java"), "direction", "OUTGOING"),
                true, 404, "INDEX_GENERATION_EXPIRED");
        assertParity(http, tools, fixture.mapper, "find_relations", "/api/v1/relations",
                Map.of("context", Map.of("repositoryId", "other", "revision", SourceFilesystemFixture.SHA),
                        "indexGeneration", "00000000-0000-0000-0000-000000000001",
                        "anchor", Map.of("kind", "SOURCE_SYMBOL", "symbolId", "0".repeat(64)), "direction", "INCOMING"),
                true, 404, "REPOSITORY_NOT_FOUND");
        assertParity(http, tools, fixture.mapper, "find_relations", "/api/v1/relations",
                Map.of("context", Map.of("repositoryId", "sample", "revision", "f".repeat(40)),
                        "indexGeneration", "00000000-0000-0000-0000-000000000001",
                        "anchor", Map.of("kind", "SOURCE_SYMBOL", "symbolId", "0".repeat(64)), "direction", "INCOMING"),
                true, 404, "REVISION_NOT_PREPARED");
        assertParity(http, tools, fixture.mapper, "get_context", "/api/v1/context",
                Map.of("repositoryId", "other"), true, 404, "REPOSITORY_NOT_FOUND");
        assertParity(http, tools, fixture.mapper, "get_context", "/api/v1/context",
                Map.of("repositoryId", "sample", "revision", "f".repeat(40)), true, 404, "REVISION_NOT_PREPARED");
        assertParity(http, tools, fixture.mapper, "read_source", "/api/v1/source",
                Map.of("context", context, "path", "missing.java"), true, 404, "SOURCE_NOT_FOUND");
        assertParity(http, tools, fixture.mapper, "read_source", "/api/v1/source",
                Map.of("context", Map.of("repositoryId", "sample", "revision", "main"), "path", "src/Order.java"),
                true, 400, "INVALID_ARGUMENT");
        assertParity(http, tools, fixture.mapper, "search_code", "/api/v1/search-code",
                Map.of("context", context, "query", "Order", "match", "contains"), true, 400, "INVALID_ARGUMENT");
        assertParity(http, tools, fixture.mapper, "search_code", "/api/v1/search-code",
                Map.of("context", context, "query", "Order", "match", "regex"), true, 400, "INVALID_ARGUMENT");
        assertParity(http, tools, fixture.mapper, "search_code", "/api/v1/search-code",
                Map.of("context", context, "query", "Order", "match", "exact", "regex", true),
                true, 400, "INVALID_ARGUMENT");
        assertParity(http, tools, fixture.mapper, "search_code", "/api/v1/search-code",
                Map.of("context", Map.of("repositoryId", "sample", "revision", "main"),
                        "query", "Order", "match", "exact"), true, 400, "INVALID_ARGUMENT");
        assertThat(facade.getContext(new ContextRequest("sample", Optional.empty())).context()).contains(fixture.context);
    }

    @Test
    void transport_schemas_expose_capabilities_for_discovery_syntax_and_static_relations() throws Exception {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        JsonNode input = mapper.valueToTree(com.java.semantic.mcp.SemanticMcpSchemaCatalog.inputSchema("find_relations"));
        assertThat(input.get("additionalProperties").asBoolean()).isFalse();
        assertThat(input.get("required").toString()).contains("context", "indexGeneration", "anchor", "direction");
        assertThat(input.get("properties").get("anchor").get("oneOf").size()).isEqualTo(3);
        assertThat(input.get("allOf").size()).isEqualTo(2);
        for (String operation : List.of("search_code", "get_outline", "list_entry_points", "find_relations")) {
            JsonNode result = mapper.valueToTree(com.java.semantic.mcp.SemanticMcpSchemaCatalog.outputSchema(operation))
                    .get("oneOf").get(0);
            assertThat(result.get("required").toString()).contains("capabilities");
            JsonNode capabilities = result.get("properties").get("capabilities");
            assertThat(capabilities.get("additionalProperties").asBoolean()).isFalse();
            assertThat(capabilities.get("required").toString()).contains("syntax", "folds", "relations");
        }
        JsonNode discovery = mapper.valueToTree(com.java.semantic.mcp.SemanticMcpSchemaCatalog.outputSchema("get_context"))
                .get("oneOf").get(0).get("properties").get("index").get("anyOf").get(0);
        assertThat(discovery.get("required").toString()).contains("capabilities");
        assertThat(discovery.get("properties").get("capabilities").get("required").toString()).contains("syntax", "folds", "relations");
        try (java.io.InputStream document = HttpMcpParityTest.class.getResourceAsStream("/openapi/semantic-api-v1.yaml")) {
            JsonNode schemas = mapper.readTree(java.util.Objects.requireNonNull(document)).get("components").get("schemas");
            assertThat(schemas.get("FindRelationsRequest").get("allOf").size()).isEqualTo(2);
            for (String schema : List.of("IndexSummary", "IndexDeclarationResult", "IndexEntryPointResult", "RelationResult")) {
                assertThat(schemas.get(schema).get("required").toString()).contains("capabilities");
                assertThat(schemas.get(schema).get("properties").get("capabilities").get("$ref").asString())
                        .isEqualTo("#/components/schemas/IndexCapabilities");
            }
            assertThat(schemas.get("IndexCapabilities").get("required").toString()).contains("syntax", "folds", "relations");
        }
    }


    private static void assertParity(MockMvc http, List<McpServerFeatures.SyncToolSpecification> tools,
            tools.jackson.databind.ObjectMapper mapper, String operation, String path, Map<String, Object> input,
            boolean expectedError, int expectedStatus, String expectedCode) throws Exception {
        String payload = (operation.equals("list_repositories")
                ? http.perform(get(path).header(QueryTokenFilter.TOKEN_HEADER, "query-token"))
                : http.perform(post(path).header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content(mapper.writeValueAsString(input))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        McpServerFeatures.SyncToolSpecification tool = tools.stream()
                .filter(value -> value.tool().name().equals(operation)).findFirst().orElseThrow();
        McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                McpSchema.CallToolRequest.builder(operation).arguments(input).build());
        JsonNode httpJson = mapper.readTree(payload);
        assertThat(httpJson).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcp.structuredContent())));
        assertThat(mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text())).isEqualTo(httpJson);
        assertThat(mcp.isError()).isEqualTo(expectedError);
        if (expectedCode != null) assertThat(httpJson.get("code").asString()).isEqualTo(expectedCode); // cs-allow
        assertThat(payload).doesNotContain("/published/", "password", "source-admin");
    }
}
