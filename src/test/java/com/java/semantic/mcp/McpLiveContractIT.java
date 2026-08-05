package com.java.semantic.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the deployed stateless MCP surface against the pinned Java System Agent revision. */
@EnabledIfEnvironmentVariable(named = "M5_SEMANTIC_BASE_URL", matches = ".+")
class McpLiveContractIT {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SOURCE_FILE =
            "src/main/java/com/java/system/agent/codeintelligence/semantic/JavaSemanticServiceHttpAdapter.java";
    private static final String PACKAGE_NAME = "com.java.system.agent.codeintelligence.semantic";
    private static final String CLASS_NAME = "JavaSemanticServiceHttpAdapter";
    private static final String METHOD_NAME = "availableRepositories";
    private static final String STALE_REVISION = "0".repeat(40);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final JsonMapper objectMapper = new JsonMapper();

    @Test
    void should_publish_the_exact_portable_read_only_catalog() throws Exception {
        JsonNode initialized = post(initializeRequest());
        assertThat(initialized.at("/result/protocolVersion").asText()).isEqualTo(PROTOCOL_VERSION);

        JsonNode tools = post(toolsListRequest()).at("/result/tools");
        assertThat(tools).hasSize(17);
        assertThat(tools)
                .extracting(tool -> tool.path("name").asText())
                .containsExactlyElementsOf(McpQueryRegistry.canonicalToolNames());

        List<String> patterns = new ArrayList<>();
        for (JsonNode tool : tools) {
            assertThat(tool.path("inputSchema").isObject()).isTrue();
            assertThat(tool.path("outputSchema").isObject()).isTrue();
            assertThat(tool.at("/annotations/readOnlyHint").asBoolean()).isTrue();
            assertThat(tool.at("/annotations/destructiveHint").asBoolean()).isFalse();
            assertThat(tool.at("/annotations/idempotentHint").asBoolean()).isTrue();
            collectPatterns(tool.path("inputSchema"), patterns);
        }

        assertThat(patterns).isNotEmpty();
        assertThat(patterns).allSatisfy(pattern ->
                assertThat(McpQuerySchemaFactory.isPortableMcpPattern(pattern)).isTrue());
    }

    @Test
    void should_execute_the_revision_pinned_mcp_query_subset() throws Exception {
        String revision = requiredEnvironment("M5_EXPECTED_REVISION");

        JsonNode repositories = successfulToolCall("semantic_list_repositories", objectMapper.createObjectNode());
        assertThat(repositories.path("repositories").isArray()).isTrue();

        JsonNode repository = successfulToolCall("semantic_get_repository", repositoryArguments())
                .path("repository");
        assertThat(repository.at("/repositoryId/value").asText()).isEqualTo(repositoryId());
        assertThat(repository.at("/currentRevision/value").asText()).isEqualTo(revision);

        JsonNode entryPoints = successfulToolCall("semantic_list_entry_points", entryPointArguments(revision));
        assertThat(entryPoints.at("/result/analyzedRevision/value").asText()).isEqualTo(revision);
        assertThat(entryPoints.at("/result/entryPoints").isArray()).isTrue();

        JsonNode lookup = successfulToolCall("semantic_lookup_api_routes", routeArguments(revision, null));
        assertThat(lookup.at("/result/matches").isArray()).isTrue();

        JsonNode suggestions = successfulToolCall("semantic_suggest_api_routes", routeArguments(revision, 3));
        assertThat(suggestions.at("/result/matches").isArray()).isTrue();

        JsonNode outgoing = successfulToolCall("semantic_analyze_outgoing_call_graph", graphArguments(revision));
        assertGraphContract(outgoing.path("result"), revision);

        JsonNode incoming = successfulToolCall("semantic_analyze_incoming_call_graph", graphArguments(revision));
        assertGraphContract(incoming.path("result"), revision);
    }

    @Test
    void should_reject_a_request_without_an_api_token() throws Exception {
        JsonNode response = postWithoutToken(toolsListRequest());

        assertThat(response.path("errorCode").asText()).isEqualTo("SEMANTIC_UNAUTHORIZED");
    }

    @Test
    void should_project_unknown_repository_and_stale_revision_as_recoverable_tool_failures() throws Exception {
        JsonNode unknownRepository = failedToolCall("semantic_get_repository", repositoryArguments("missing-repository"));
        JsonNode unknownFailure = failureContent(unknownRepository);
        assertThat(unknownFailure.path("errorCode").asText()).isEqualTo("REPOSITORY_NOT_FOUND");

        JsonNode staleRevision = failedToolCall("semantic_list_entry_points", entryPointArguments(STALE_REVISION));
        JsonNode staleFailure = failureContent(staleRevision);
        assertThat(staleFailure.path("errorCode").asText()).isEqualTo("REPOSITORY_REVISION_MISMATCH");
        assertThat(staleFailure.at("/requestIdentity/expectedRevision").asText()).isEqualTo(STALE_REVISION);
        assertThat(staleFailure.at("/requestIdentity/currentRevision").asText())
                .isEqualTo(requiredEnvironment("M5_EXPECTED_REVISION"));
        assertThat(staleFailure.at("/recovery/availableFollowUps/0/toolName").asText())
                .isEqualTo("semantic_get_repository");
        assertThat(staleFailure.at("/recovery/availableFollowUps/0/arguments/repoId").asText())
                .isEqualTo(repositoryId());
    }

    @Test
    void should_reject_a_blank_api_path_without_returning_structured_content() throws Exception {
        String revision = requiredEnvironment("M5_EXPECTED_REVISION");
        ObjectNode arguments = routeArguments(revision, null);
        arguments.put("apiPath", "");

        JsonNode response = post(toolCallRequest("semantic_lookup_api_routes", arguments));
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/structuredContent").isObject()).isFalse();

        JsonNode content = response.at("/result/content/0/text");
        if (content.isTextual() && StringUtils.hasText(content.asText()) && content.asText().startsWith("{")) {
            JsonNode failure = objectMapper.readTree(content.asText());
            assertThat(failure.path("errorCode").asText()).isEqualTo("INVALID_TOOL_INPUT");
        }
    }

    private JsonNode successfulToolCall(String toolName, ObjectNode arguments) throws Exception {
        JsonNode response = post(toolCallRequest(toolName, arguments));
        JsonNode result = response.path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.has("structuredContent")).isTrue();
        return result.path("structuredContent");
    }

    private JsonNode failedToolCall(String toolName, ObjectNode arguments) throws Exception {
        JsonNode response = post(toolCallRequest(toolName, arguments));
        assertThat(response.at("/result/isError").asBoolean()).isTrue();
        assertThat(response.at("/result/structuredContent").isObject()).isFalse();
        return response;
    }

    private JsonNode post(String payload) throws IOException, InterruptedException {
        return post(payload, true);
    }

    private JsonNode post(JsonNode payload) throws IOException, InterruptedException {
        return post(objectMapper.writeValueAsString(payload));
    }

    private JsonNode postWithoutToken(String payload) throws IOException, InterruptedException {
        return post(payload, false);
    }

    private JsonNode postWithoutToken(JsonNode payload) throws IOException, InterruptedException {
        return post(objectMapper.writeValueAsString(payload), false);
    }

    private JsonNode post(String payload, boolean includeToken) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(mcpEndpoint())
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (includeToken) {
            request.header("X-Api-Token", requiredEnvironment("M5_SEMANTIC_API_TOKEN"));
        }
        HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int expectedStatus = includeToken ? 200 : 401;
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return objectMapper.readTree(response.body());
    }

    private URI mcpEndpoint() {
        String baseUrl = requiredEnvironment("M5_SEMANTIC_BASE_URL");
        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return URI.create(normalizedBaseUrl + "/mcp");
    }

    private ObjectNode initializeRequest() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "m5-live-contract");
        clientInfo.put("version", "1");
        return jsonRpcRequest(1, "initialize", params);
    }

    private ObjectNode toolsListRequest() {
        return jsonRpcRequest(2, "tools/list", objectMapper.createObjectNode());
    }

    private ObjectNode toolCallRequest(String toolName, ObjectNode arguments) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        return jsonRpcRequest(3, "tools/call", params);
    }

    private ObjectNode jsonRpcRequest(int id, String method, ObjectNode params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        return request;
    }

    private ObjectNode repositoryArguments() {
        return repositoryArguments(repositoryId());
    }

    private ObjectNode repositoryArguments(String repositoryId) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("repoId", repositoryId);
        return arguments;
    }

    private ObjectNode entryPointArguments(String revision) {
        ObjectNode arguments = revisionPinnedArguments(revision);
        ArrayNode types = arguments.putArray("types");
        types.add("API");
        return arguments;
    }

    private ObjectNode routeArguments(String revision, Integer limit) {
        ObjectNode arguments = revisionPinnedArguments(revision);
        arguments.put("apiPath", "/v1/repositories");
        if (Objects.nonNull(limit)) {
            arguments.put("limit", limit);
        }
        return arguments;
    }

    private ObjectNode graphArguments(String revision) {
        ObjectNode arguments = revisionPinnedArguments(revision);
        arguments.put("depth", 1);
        ObjectNode target = arguments.putObject("target");
        ObjectNode sourceType = target.putObject("sourceType");
        ObjectNode javaType = sourceType.putObject("javaType");
        javaType.put("packageName", PACKAGE_NAME);
        javaType.put("className", CLASS_NAME);
        sourceType.put("sourceFile", SOURCE_FILE);
        target.put("methodName", METHOD_NAME);
        target.putArray("parameterTypes");
        return arguments;
    }

    private ObjectNode revisionPinnedArguments(String revision) {
        ObjectNode arguments = repositoryArguments();
        arguments.put("expectedRevision", revision);
        return arguments;
    }

    private void assertGraphContract(JsonNode graph, String revision) {
        assertThat(graph.at("/analyzedRevision/value").asText()).isEqualTo(revision);
        JsonNode traversal = graph.path("traversal");
        assertThat(traversal.path("requestedDepth").asInt()).isEqualTo(1);
        assertThat(traversal.path("expandedNodeCount").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(traversal.path("nodeBudget").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(traversal.path("expandedNodeCount").asInt())
                .isLessThanOrEqualTo(traversal.path("nodeBudget").asInt());
        assertThat(traversal.path("rootDirectCallsComplete").asBoolean()).isTrue();
        validateSourceRanges(graph);
        validateFollowUps(graph, revision);
    }

    private void validateSourceRanges(JsonNode candidate) {
        if (candidate.isObject()) {
            if (candidate.has("sourceFile") && candidate.has("startLine")) {
                assertFlatSourceRange(candidate);
            }
            if (candidate.has("sourceFile") && candidate.path("range").isObject()) {
                assertNestedSourceRange(candidate.path("range"));
            }
        }
        List<JsonNode> children = candidate.valueStream().toList();
        for (JsonNode child : children) {
            validateSourceRanges(child);
        }
    }

    private void assertFlatSourceRange(JsonNode range) {
        int startLine = range.path("startLine").asInt();
        int startCharacter = range.path("startCharacter").asInt();
        int endLine = range.path("endLine").asInt();
        int endCharacter = range.path("endCharacter").asInt();
        assertThat(range.path("sourceFile").asText()).isNotBlank();
        assertThat(startLine).isGreaterThanOrEqualTo(0);
        assertThat(startCharacter).isGreaterThanOrEqualTo(0);
        assertThat(endLine).isGreaterThanOrEqualTo(startLine);
        assertThat(endCharacter).isGreaterThanOrEqualTo(0);
        assertThat(endLine > startLine || endCharacter >= startCharacter).isTrue();
    }

    private void assertNestedSourceRange(JsonNode range) {
        JsonNode start = range.path("start");
        JsonNode end = range.path("end");
        int startLine = start.path("line").asInt();
        int startCharacter = start.path("character").asInt();
        int endLine = end.path("line").asInt();
        int endCharacter = end.path("character").asInt();
        assertThat(startLine).isGreaterThanOrEqualTo(0);
        assertThat(startCharacter).isGreaterThanOrEqualTo(0);
        assertThat(endLine).isGreaterThanOrEqualTo(startLine);
        assertThat(endCharacter).isGreaterThanOrEqualTo(0);
        assertThat(endLine > startLine || endCharacter >= startCharacter).isTrue();
    }

    private void validateFollowUps(JsonNode candidate, String revision) {
        if (candidate.isObject() && candidate.has("availableFollowUps")) {
            JsonNode followUps = candidate.path("availableFollowUps");
            assertThat(followUps.isArray()).isTrue();
            for (JsonNode followUp : followUps) {
                assertThat(followUp.path("toolName").asText()).isNotBlank();
                assertThat(followUp.path("arguments").path("repoId").asText()).isEqualTo(repositoryId());
                assertThat(followUp.path("arguments").path("expectedRevision").asText()).isEqualTo(revision);
            }
        }
        List<JsonNode> children = candidate.valueStream().toList();
        for (JsonNode child : children) {
            validateFollowUps(child, revision);
        }
    }

    private JsonNode failureContent(JsonNode response) throws JacksonException {
        return objectMapper.readTree(response.at("/result/content/0/text").asText());
    }

    private void collectPatterns(JsonNode candidate, List<String> patterns) {
        JsonNode pattern = candidate.path("pattern");
        if (pattern.isTextual()) {
            patterns.add(pattern.asText());
        }
        List<JsonNode> children = candidate.valueStream().toList();
        for (JsonNode child : children) {
            collectPatterns(child, patterns);
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("required environment variable is blank: " + name);
        }
        return value;
    }

    private String repositoryId() {
        return requiredEnvironment("M5_REPO_ID");
    }
}
