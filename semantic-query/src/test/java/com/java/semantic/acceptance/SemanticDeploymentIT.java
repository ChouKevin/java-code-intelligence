package com.java.semantic.acceptance;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("deployed-it")
class SemanticDeploymentIT {

    private static final String TOKEN_HEADER = "X-Api-Token";
    private static final List<String> TOOL_NAMES = List.of(
            "list_repositories", "get_repository", "search_code", "get_fact_source", "list_entry_points", "find_api_routes",
            "find_event_listeners", "list_type_members", "find_method_implementations", "find_references", "find_callers", "find_callees");

    @Test
    void deployed_query_exposes_the_twelve_tool_contract_and_matches_http_revision_errors() throws Exception {
        String baseUrl = requiredEnvironment("SEMANTIC_BASE_URL");
        String apiToken = requiredEnvironment("SEMANTIC_API_TOKEN");
        String repositoryId = requiredEnvironment("SEMANTIC_UAT_REPOSITORY");
        JsonMapper mapper = JsonMapper.builder().build();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(mcpEndpoint(baseUrl))
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .httpRequestCustomizer((request, method, uri, body, context) -> request.header(TOKEN_HEADER, apiToken))
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(15))
                .initializationTimeout(Duration.ofSeconds(15))
                .build()) {
            McpSchema.InitializeResult initialization = client.initialize();
            assertThat(initialization.serverInfo()).isNotNull();

            McpSchema.ListToolsResult tools = client.listTools();
            assertThat(tools.tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrderElementsOf(TOOL_NAMES);

            Map<?, ?> repositories = successfulBody(client.callTool(new McpSchema.CallToolRequest("list_repositories", Map.of())), mapper);
            Map<?, ?> repository = repository(repositories, repositoryId);
            String revision = String.valueOf(repository.get("revision"));

            Map<?, ?> search = successfulBody(client.callTool(new McpSchema.CallToolRequest("search_code", Map.of(
                    "repositoryId", repositoryId, "revision", revision, "query", "payment"))), mapper);
            Map<?, ?> fact = firstItem(search, "search_code");
            String factId = String.valueOf(fact.get("factId"));

            Map<?, ?> factSource = successfulBody(client.callTool(new McpSchema.CallToolRequest("get_fact_source", Map.of(
                    "repositoryId", repositoryId, "revision", revision, "factId", factId))), mapper);
            assertThat(factSource.get("repositoryId")).isEqualTo(repositoryId);
            assertThat(factSource.get("revision")).isEqualTo(revision);
            assertThat(factSource.get("factId")).isEqualTo(factId);
            assertThat(factSource.get("source")).isInstanceOf(Map.class);

            Map<String, Object> outdatedRequest = Map.of(
                    "repositoryId", repositoryId, "revision", "0".repeat(40), "query", "payment");
            HttpResponse<String> httpResponse = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create(httpEndpoint(baseUrl, "/api/v1/search-code")))
                    .header(TOKEN_HEADER, apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(outdatedRequest)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(httpResponse.statusCode()).isEqualTo(409);

            McpSchema.CallToolResult mcpResult = client.callTool(new McpSchema.CallToolRequest("search_code", outdatedRequest));
            assertThat(mcpResult.isError()).isTrue();
            Map<?, ?> httpError = mapper.readValue(httpResponse.body(), Map.class);
            Map<?, ?> mcpError = mapper.convertValue(mcpResult.structuredContent(), Map.class);
            assertThat(mcpError).isEqualTo(httpError);
            assertThat(mcpError.get("code")).isEqualTo("REVISION_OUTDATED");
            assertThat(mcpError.get("currentRevision")).isEqualTo(revision);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(StringUtils.hasText(value)).as("%s must be configured", name).isTrue();
        return value;
    }

    private static String mcpEndpoint(String baseUrl) {
        return httpEndpoint(baseUrl, "/mcp");
    }

    private static String httpEndpoint(String baseUrl, String path) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
    }

    private static Map<?, ?> successfulBody(McpSchema.CallToolResult result, JsonMapper mapper) {
        assertThat(result.isError()).isFalse();
        return mapper.convertValue(result.structuredContent(), Map.class);
    }

    private static Map<?, ?> repository(Map<?, ?> repositories, String repositoryId) {
        Object items = repositories.get("items");
        assertThat(items).isInstanceOf(List.class);
        return ((List<?>) items).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(item -> repositoryId.equals(item.get("repositoryId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("configured repository is not visible through list_repositories"));
    }

    private static Map<?, ?> firstItem(Map<?, ?> result, String toolName) {
        Object items = result.get("items");
        assertThat(items).as("%s result items", toolName).isInstanceOf(List.class);
        return ((List<?>) items).stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(toolName + " did not return a fact for the deployed fixture"));
    }
}
