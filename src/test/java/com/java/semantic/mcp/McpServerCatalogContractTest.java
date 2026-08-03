package com.java.semantic.mcp;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.trie.ApiRouteApplicationService;
import com.java.semantic.trie.ApiRouteIndexNotReadyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 驗證公開 MCP endpoint 的 catalog 與 JSON-RPC 失敗契約 */
@SpringBootTest(properties = "semantic.api.api-token=test-token")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class McpServerCatalogContractTest {

    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApiRouteApplicationService apiRouteApplicationService;

    @MockitoBean
    private ConceptDiscoveryApplicationService conceptDiscoveryApplicationService;

    @MockitoBean
    private SemanticAnalysisApplicationService semanticAnalysisApplicationService;

    @Test
    void should_initialize_and_publish_exactly_the_canonical_read_only_catalog() throws Exception {
        mockMvc.perform(mcpRequest(initializeRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));

        MvcResult result = mockMvc.perform(mcpRequest(toolsListRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(17))
                .andReturn();

        JsonNode tools = objectMapper.readTree(result.getResponse().getContentAsString()).path("result").path("tools");
        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactlyElementsOf(McpQueryRegistry.canonicalToolNames());
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean()).isFalse();
            assertThat(tool.path("annotations").path("idempotentHint").asBoolean()).isTrue();
        });
    }

    @Test
    void should_keep_malformed_unknown_and_invalid_tool_calls_distinct() throws Exception {
        MvcResult malformed = mockMvc.perform(mcpRequest("{invalid}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(malformed.getResponse().getContentAsString()).doesNotContain("stackTrace");

        mockMvc.perform(mcpRequest(toolCallRequest("unissued_tool", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Unknown tool: invalid_tool_name"));

        mockMvc.perform(mcpRequest(toolCallRequest("semantic_get_repository", "{\"repoId\":\"\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    void should_record_successful_mcp_invocation_without_logging_raw_json_rpc_payload(CapturedOutput output)
            throws Exception {
        mockMvc.perform(mcpRequest(toolCallRequest("semantic_list_repositories", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        assertThat(output)
                .contains("mcp_tool_completed")
                .contains("toolName=semantic_list_repositories resultCategory=SUCCESS")
                .doesNotContain("\"jsonrpc\":\"2.0\"");
    }

    @Test
    void should_return_typed_expected_query_failures_with_recovery_metadata(CapturedOutput output) throws Exception {
        RepositoryRevision requestedRevision = RepositoryRevision.ofSha("1".repeat(40));
        RepositoryRevision currentRevision = RepositoryRevision.ofSha("2".repeat(40));
        RepositoryId repositoryId = RepositoryId.of("orders");

        assertRouteFailure(
                new RepositoryRevisionMismatchException(requestedRevision, currentRevision),
                "REPOSITORY_REVISION_MISMATCH",
                true,
                requestedRevision.value(),
                currentRevision.value());
        assertRouteFailure(
                new ApiRouteIndexNotReadyException(repositoryId, requestedRevision),
                "API_ROUTE_INDEX_NOT_READY",
                true,
                "",
                "");
        assertRouteFailure(
                new RepositoryNotFoundException(repositoryId),
                "REPOSITORY_NOT_FOUND",
                false,
                "",
                "");
        assertRouteFailure(
                new RepositoryNotReadyException(repositoryId),
                "REPOSITORY_NOT_READY",
                true,
                "",
                "");

        assertThat(output).contains("toolName=semantic_lookup_api_routes resultCategory=EXPECTED_TOOL_FAILURE");
    }

    @Test
    void should_publish_recursive_constraints_and_optional_polymorphic_inputs_that_tools_call_accepts() throws Exception {
        JsonNode tools = tools();
        JsonNode routeInput = tool(tools, "semantic_suggest_api_routes").path("inputSchema");
        assertThat(routeInput.at("/properties/repoId/pattern").asText())
                .isEqualTo("^[a-z0-9][a-z0-9._-]{0,63}$");
        assertThat(routeInput.at("/properties/expectedRevision/pattern").asText())
                .isEqualTo("^[0-9a-f]{40}$|^FIXTURE$");
        assertThat(routeInput.at("/properties/limit/minimum").asInt()).isEqualTo(1);
        assertThat(routeInput.at("/properties/limit/maximum").asInt()).isEqualTo(20);

        JsonNode resolveInput = tool(tools, "semantic_resolve_concept").path("inputSchema");
        assertOptionalProperty(resolveInput, "triggerValue");
        assertOptionalProperty(resolveInput, "databaseId");
        assertRequiredProperty(tool(tools, "semantic_find_internal_references").path("inputSchema"), "line");
        assertRequiredProperty(tool(tools, "semantic_find_internal_references").path("inputSchema"), "character");

        willThrow(new RepositoryNotReadyException(RepositoryId.of("orders")))
                .given(conceptDiscoveryApplicationService).resolve(any());
        assertConceptInputIsAccepted(scheduleIdentityWithoutTriggerValue());
        assertConceptInputIsAccepted(mapperIdentityWithoutDatabaseId());
    }

    @Test
    void should_return_typed_semantic_engine_failures_from_mcp_transport() throws Exception {
        willThrow(new SemanticProtocolException()).given(semanticAnalysisApplicationService)
                .analyzeOutgoing(any(), any(), any(), anyInt());

        mockMvc.perform(mcpRequest(toolCallRequest("semantic_analyze_outgoing_call_graph", """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "depth":1,
                  "target":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderController"},"sourceFile":"src/main/java/com/example/OrderController.java"},
                    "methodName":"getOrder",
                    "parameterTypes":[]
                  }
                }
                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.errorCode").value("SEMANTIC_PROTOCOL_ERROR"))
                .andExpect(jsonPath("$.result.structuredContent.recovery.retryable").value(true));
    }

    @Test
    void should_keep_unknown_tool_defects_as_sanitized_internal_mcp_failures(CapturedOutput output) throws Exception {
        willThrow(new IllegalStateException("private integration defect"))
                .given(apiRouteApplicationService).lookupMatches(any(), any(), any(), any());

        MvcResult result = mockMvc.perform(mcpRequest(toolCallRequest(
                        "semantic_lookup_api_routes",
                        "{\"repoId\":\"orders\",\"expectedRevision\":\"1111111111111111111111111111111111111111\",\"apiPath\":\"/orders\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.result.structuredContent.message").value("request failed"))
                .andExpect(jsonPath("$.result.structuredContent.recovery.retryable").value(false))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("private integration defect")
                .doesNotContain("INVALID_TOOL_INPUT");
        assertThat(output).contains("toolName=semantic_lookup_api_routes resultCategory=APPLICATION_FAILURE");
    }

    @Test
    void should_reject_unauthenticated_mcp_requests_with_the_existing_api_error_contract() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("application/json, text/event-stream")
                        .header("MCP-Protocol-Version", "2025-06-18")
                        .content(toolsListRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcpRequest(String payload) {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .content(payload);
    }

    private String initializeRequest() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"contract-test","version":"1"}}}
                """;
    }

    private String toolsListRequest() {
        return """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """;
    }

    private String toolCallRequest(String toolName, String arguments) {
        return """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(toolName, arguments);
    }

    private void assertRouteFailure(
            RuntimeException failure,
            String errorCode,
            boolean retryable,
            String expectedRevision,
            String currentRevision) throws Exception {
        willThrow(failure).given(apiRouteApplicationService).lookupMatches(any(), any(), any(), any());

        MvcResult result = mockMvc.perform(mcpRequest(toolCallRequest(
                        "semantic_lookup_api_routes",
                        "{\"repoId\":\"orders\",\"expectedRevision\":\"1111111111111111111111111111111111111111\",\"apiPath\":\"/orders\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.errorCode").value(errorCode))
                .andExpect(jsonPath("$.result.structuredContent.recovery.retryable").value(retryable))
                .andReturn();

        JsonNode failureContent = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/result/structuredContent");
        assertThat(failureContent.path("expectedRevision").asText()).isEqualTo(expectedRevision);
        assertThat(failureContent.path("currentRevision").asText()).isEqualTo(currentRevision);
    }

    private JsonNode tools() throws Exception {
        MvcResult result = mockMvc.perform(mcpRequest(toolsListRequest()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/result/tools");
    }

    private JsonNode tool(JsonNode tools, String name) {
        return tools.valueStream()
                .filter(tool -> name.equals(tool.path("name").asText()))
                .findFirst()
                .orElseThrow();
    }

    private void assertOptionalProperty(JsonNode schema, String propertyName) {
        List<JsonNode> owners = schemaOwners(schema, propertyName);
        assertThat(owners).isNotEmpty();
        assertThat(owners).allSatisfy(owner -> assertThat(owner.path("required"))
                .extracting(JsonNode::asText)
                .doesNotContain(propertyName));
    }

    private void assertRequiredProperty(JsonNode schema, String propertyName) {
        List<JsonNode> owners = schemaOwners(schema, propertyName);
        assertThat(owners).isNotEmpty();
        assertThat(owners).allSatisfy(owner -> assertThat(owner.path("required"))
                .extracting(JsonNode::asText)
                .contains(propertyName));
    }

    private List<JsonNode> schemaOwners(JsonNode schema, String propertyName) {
        List<JsonNode> owners = new ArrayList<>();
        collectSchemaOwners(schema, propertyName, owners);
        return owners;
    }

    private void collectSchemaOwners(JsonNode node, String propertyName, List<JsonNode> owners) {
        JsonNode properties = node.path("properties");
        if (properties.has(propertyName)) {
            owners.add(node);
        }
        for (JsonNode child : node) {
            collectSchemaOwners(child, propertyName, owners);
        }
    }

    private void assertConceptInputIsAccepted(String identity) throws Exception {
        mockMvc.perform(mcpRequest(toolCallRequest("semantic_resolve_concept", """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":%s
                }
                """.formatted(identity))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent.errorCode").value("REPOSITORY_NOT_READY"));
    }

    private String scheduleIdentityWithoutTriggerValue() {
        return """
                {
                  "kind":"SCHEDULE",
                  "target":{
                    "sourceType":{"javaType":{"packageName":"com.example","className":"OrderController"},"sourceFile":"src/main/java/com/example/OrderController.java"},
                    "methodName":"getOrder",
                    "parameterTypes":[]
                  },
                  "triggerKind":"CRON"
                }
                """;
    }

    private String mapperIdentityWithoutDatabaseId() {
        return """
                {
                  "kind":"MAPPER_STATEMENT_VARIANT",
                  "identity":{
                    "statementKey":{"namespace":"com.example.OrderMapper","statementId":"findOrder"},
                    "resourcePath":"mapper/OrderMapper.xml",
                    "documentOrdinal":0,
                    "representation":"MAPPER_XML_ELEMENT"
                  }
                }
                """;
    }
}
