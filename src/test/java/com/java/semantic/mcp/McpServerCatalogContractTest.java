package com.java.semantic.mcp;

import com.java.semantic.api.security.ApiTokenFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.error.code").value(-32603))
                .andExpect(jsonPath("$.error.message").value("INVALID_TOOL_INPUT"));
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
}
