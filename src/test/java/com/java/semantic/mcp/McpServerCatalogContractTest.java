package com.java.semantic.mcp;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.mcp.dto.callgraph.CallGraphMcpDtos;
import com.java.semantic.mcp.dto.framework.FrameworkDiscoveryMcpDtos;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.syntax.application.concept.ConceptDiscoveryApplicationService;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryNotReadyException;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryMode;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.trie.ApiRouteApplicationService;
import com.java.semantic.trie.ApiRouteIndexNotReadyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.given;
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

    @MockitoBean
    private RepositoryApplicationService repositoryApplicationService;

    @Test
    void should_initialize_and_publish_exactly_the_canonical_read_only_catalog() throws Exception {
        mockMvc.perform(mcpRequest(initializeRequest("2025-06-18"), "2025-06-18"))
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

    @ParameterizedTest
    @ValueSource(strings = {"2025-06-18", "2025-11-25"})
    void should_initialize_supported_project_protocol_versions(String protocolVersion) throws Exception {
        mockMvc.perform(mcpRequest(initializeRequest(protocolVersion), protocolVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value(protocolVersion));
    }

    @Test
    void should_keep_malformed_unknown_and_invalid_tool_calls_distinct() throws Exception {
        MvcResult malformed = mockMvc.perform(mcpRequest("{private-error-data}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(malformed.getResponse().getContentAsString())
                .doesNotContain(
                        "stackTrace",
                        "McpError",
                        "JacksonException",
                        "private-error-data");

        mockMvc.perform(mcpRequest(toolCallRequest("unissued_tool", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602))
                .andExpect(jsonPath("$.error.message").value("Unknown tool: invalid_tool_name"));

        mockMvc.perform(mcpRequest(toolCallRequest("semantic_get_repository", "{\"repoId\":\"\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true));
    }

    @Test
    void should_leave_schema_rejection_to_the_sdk_before_callback_monitoring(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(mcpRequest(toolCallRequest("semantic_get_repository", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("INVALID_TOOL_INPUT");
        assertThat(output).doesNotContain("toolName=semantic_get_repository");
    }

    @Test
    void should_map_java_identifier_validation_to_project_invalid_tool_input(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(mcpRequest(toolCallRequest(
                        "semantic_resolve_concept",
                        conceptResolveRequest("invalid-method-name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andReturn();

        JsonNode failure = failureContent(result);
        assertThat(failure.path("errorCode").asText()).isEqualTo("INVALID_TOOL_INPUT");
        assertThat(output).contains("toolName=semantic_resolve_concept resultCategory=INVALID_TOOL_INPUT");
    }

    @Test
    void should_record_successful_mcp_invocation_without_logging_raw_json_rpc_payload(CapturedOutput output)
            throws Exception {
        mockMvc.perform(mcpRequest(toolCallRequest("semantic_list_repositories", "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.repositories").isArray())
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andExpect(jsonPath("$.result.content[0].text").value("Query completed"));

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
                false,
                requestedRevision.value(),
                currentRevision.value());
        assertRouteFailure(
                new ApiRouteIndexNotReadyException(repositoryId, requestedRevision),
                "API_ROUTE_INDEX_NOT_READY",
                true,
                requestedRevision.value(),
                "");
        assertRouteFailure(
                new RepositoryNotFoundException(repositoryId),
                "REPOSITORY_NOT_FOUND",
                false,
                requestedRevision.value(),
                "");
        assertRouteFailure(
                new RepositoryNotReadyException(repositoryId),
                "REPOSITORY_NOT_READY",
                true,
                requestedRevision.value(),
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
    void should_publish_complete_required_output_schemas_with_nullable_values() throws Exception {
        JsonNode outputSchema = tool(tools(), "semantic_get_repository").path("outputSchema");

        assertRequiredProperty(outputSchema, "repository");
        assertRequiredProperty(outputSchema, "repositoryId");
        List<JsonNode> currentRevisionOwners = schemaOwners(outputSchema, "currentRevision");
        assertThat(currentRevisionOwners).isNotEmpty();
        assertThat(currentRevisionOwners).allSatisfy(owner -> {
            assertThat(owner.path("required")).extracting(JsonNode::asText).contains("currentRevision");
            assertThat(owner.path("properties").path("currentRevision").path("type"))
                    .extracting(JsonNode::asText)
                    .contains("object", "null");
        });
    }

    @Test
    void should_publish_closed_graph_follow_up_schema_variants() throws Exception {
        JsonNode outputSchema = tool(tools(), "semantic_analyze_outgoing_call_graph").path("outputSchema");
        List<JsonNode> edgeSchemas = schemaOwners(outputSchema, "availableFollowUps");

        assertThat(edgeSchemas).hasSize(1);
        JsonNode branches = edgeSchemas.getFirst().at("/properties/availableFollowUps/items/anyOf");
        assertThat(branches).hasSize(2);
        assertGraphFollowUpBranch(branches, "semantic_get_evidence_source", "identity");
        assertGraphFollowUpBranch(branches, "semantic_discover_method_implementations", "declarationTarget");

        MethodTarget declarationTarget = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", "OrderPort"),
                        "src/main/java/com/example/OrderPort.java"),
                "findOrder",
                List.of("String"));
        CallGraphMcpDtos.GraphFollowUp followUp = new CallGraphMcpDtos.MethodImplementationsFollowUp(
                "semantic_discover_method_implementations",
                new FrameworkDiscoveryMcpDtos.MethodImplementationsInput("orders", "FIXTURE", declarationTarget));
        String serialized = objectMapper.writeValueAsString(followUp);
        JsonNode serializedFollowUp = objectMapper.readTree(serialized);

        assertThat(serialized.split("\"toolName\"", -1)).hasSize(2);
        assertThat(serializedFollowUp.size()).isEqualTo(2);
        assertThat(serializedFollowUp.path("toolName").asText())
                .isEqualTo("semantic_discover_method_implementations");
        assertThat(serializedFollowUp.at("/arguments/declarationTarget/methodName").asText()).isEqualTo("findOrder");
    }

    @Test
    void should_serialize_nullable_output_components_as_explicit_nulls() throws Exception {
        given(repositoryApplicationService.status(RepositoryId.of("orders"))).willReturn(new RepositoryStatus(
                RepositoryId.of("orders"),
                RepositoryMode.REMOTE,
                "orders",
                Optional.empty(),
                Optional.empty(),
                true));

        MvcResult result = mockMvc.perform(mcpRequest(toolCallRequest(
                        "semantic_get_repository", "{\"repoId\":\"orders\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andReturn();

        JsonNode repository = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/result/structuredContent/repository");
        assertThat(repository.has("currentBranch")).isTrue();
        assertThat(repository.path("currentBranch").isNull()).isTrue();
        assertThat(repository.has("currentRevision")).isTrue();
        assertThat(repository.path("currentRevision").isNull()).isTrue();
    }

    @Test
    void should_publish_only_portable_patterns_across_all_input_schemas() throws Exception {
        JsonNode tools = tools();
        assertThat(tools).hasSize(17);
        List<String> patterns = new ArrayList<>();
        for (JsonNode tool : tools) {
            collectPatterns(tool.path("inputSchema"), patterns);
        }

        assertThat(patterns).isNotEmpty();
        assertThat(patterns).allSatisfy(pattern ->
                assertThat(McpQuerySchemaFactory.isPortableMcpPattern(pattern)).isTrue());
    }

    @Test
    void should_return_typed_semantic_engine_failures_from_mcp_transport() throws Exception {
        willThrow(new SemanticProtocolException()).given(semanticAnalysisApplicationService)
                .analyzeOutgoing(any(), any(), any(), anyInt());

        MvcResult result = mockMvc.perform(mcpRequest(toolCallRequest("semantic_analyze_outgoing_call_graph", """
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
                .andExpect(jsonPath("$.result.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andReturn();
        JsonNode failure = failureContent(result);
        assertThat(failure.path("errorCode").asText()).isEqualTo("SEMANTIC_PROTOCOL_ERROR");
        assertThat(failure.at("/recovery/retryable").asBoolean()).isTrue();
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
                .andExpect(jsonPath("$.result.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andReturn();

        JsonNode failure = failureContent(result);
        assertThat(failure.path("errorCode").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(failure.path("message").asText()).isEqualTo("request failed");
        assertThat(failure.at("/recovery/retryable").asBoolean()).isFalse();
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

    @Test
    void should_reject_present_origin_before_transport_and_callback_with_fixed_sanitized_error(CapturedOutput output)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("application/json, text/event-stream")
                        .header("Origin", "https://private-browser.example")
                        .header("MCP-Protocol-Version", "2025-06-18")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .content("{\"privateSourceBody\":\"never-return-me\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MCP_ORIGIN_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Origin header is not allowed"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "private-browser.example",
                        "never-return-me",
                        "stackTrace",
                        "Exception");
        assertThat(output)
                .contains("mcp_transport_completed")
                .contains("observedStatus=403")
                .doesNotContain("private-browser.example", "never-return-me")
                .doesNotContain("mcp_tool_completed");
    }

    private MockHttpServletRequestBuilder mcpRequest(String payload) {
        return mcpRequest(payload, "2025-06-18");
    }

    private MockHttpServletRequestBuilder mcpRequest(
            String payload,
            String protocolVersion) {
        return post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .accept("application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocolVersion)
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .content(payload);
    }

    private String initializeRequest(String protocolVersion) {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"contract-test","version":"1"}}}
                """.formatted(protocolVersion);
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
                .andExpect(jsonPath("$.result.structuredContent").doesNotExist())
                .andExpect(jsonPath("$.result.content.length()").value(1))
                .andReturn();

        JsonNode failureContent = failureContent(result);
        assertThat(failureContent.path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(failureContent.at("/recovery/retryable").asBoolean()).isEqualTo(retryable);
        assertThat(failureContent.at("/requestIdentity/repoId").asText()).isEqualTo("orders");
        assertThat(failureContent.at("/requestIdentity/expectedRevision").asText()).isEqualTo(expectedRevision);
        assertThat(failureContent.at("/requestIdentity/currentRevision").asText()).isEqualTo(currentRevision);
        if ("REPOSITORY_REVISION_MISMATCH".equals(errorCode)) {
            assertThat(failureContent.at("/recovery/availableFollowUps/0/toolName").asText())
                    .isEqualTo("semantic_get_repository");
            assertThat(failureContent.at("/recovery/availableFollowUps/0/arguments/repoId").asText())
                    .isEqualTo("orders");
        }
    }

    private JsonNode failureContent(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String failureJson = response.at("/result/content/0/text").asText();
        return objectMapper.readTree(failureJson);
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

    private void assertGraphFollowUpBranch(JsonNode branches, String toolName, String targetProperty) {
        JsonNode branch = branches.valueStream()
                .filter(candidate -> toolName.equals(candidate.at("/properties/toolName/const").asText()))
                .findFirst()
                .orElseThrow();

        assertThat(branch.path("additionalProperties").asBoolean()).isFalse();
        assertThat(branch.path("required")).extracting(JsonNode::asText).containsExactlyInAnyOrder("toolName", "arguments");
        assertThat(branch.at("/properties/toolName/const").asText()).isEqualTo(toolName);
        assertThat(branch.at("/properties/arguments/required"))
                .extracting(JsonNode::asText)
                .contains("repoId", "expectedRevision", targetProperty);
        assertThat(branch.at("/properties/arguments/properties/" + targetProperty).isMissingNode()).isFalse();
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

    private void collectPatterns(JsonNode node, List<String> patterns) {
        JsonNode pattern = node.get("pattern");
        if (pattern != null) { // cs-allow Jackson JsonNode#get signals absence with null
            patterns.add(pattern.asText());
        }
        for (JsonNode child : node) {
            collectPatterns(child, patterns);
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
                .andExpect(jsonPath("$.result.structuredContent").doesNotExist());
    }

    private String conceptResolveRequest(String methodName) {
        return """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "identity":{
                    "kind":"API_ROUTE",
                    "target":{
                      "sourceType":{
                        "javaType":{"packageName":"com.example","className":"OrderController"},
                        "sourceFile":"src/main/java/com/example/OrderController.java"
                      },
                      "methodName":"%s",
                      "parameterTypes":[]
                    },
                    "httpVerb":"GET",
                    "route":"/orders"
                  }
                }
                """.formatted(methodName);
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
