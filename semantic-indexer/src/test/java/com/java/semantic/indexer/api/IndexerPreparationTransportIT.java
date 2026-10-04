package com.java.semantic.indexer.api;

import com.java.semantic.indexer.source.LocalSourceFixture;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real source-only Spring HTTP and MCP transports, local Git, no Mongo or JDT. */
class IndexerPreparationTransportIT {
    @TempDir Path root;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void two_private_tools_share_exact_result_errors_security_and_lost_response_lookup() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            String revision = fixture.commit("A.java", "class A {}\r\n".getBytes(StandardCharsets.UTF_8), "A");
            try (ConfigurableApplicationContext context = fixture.start()) {
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                String base = "http://127.0.0.1:" + port;
                assertThat(http(base, "/index/repositories/orders/source", "POST",
                        "{\"requestId\":\"" + UUID.randomUUID() + "\"}", "query-token").statusCode()).isEqualTo(401);
                assertThat(http(base, "/mcp", "POST", "{}", "query-token").statusCode()).isEqualTo(401);
                try (McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(base + "/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(mapper))
                        .httpRequestCustomizer((request, method, uri, body, callContext) ->
                                request.header("X-Api-Token", "admin-secret")).build())
                        .requestTimeout(Duration.ofSeconds(20)).build()) {
                    client.initialize();
                    assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                            .containsExactlyInAnyOrder("prepare_source", "get_job");
                    String originalRequest = UUID.randomUUID().toString();
                    McpSchema.CallToolResult accepted = client.callTool(McpSchema.CallToolRequest.builder("prepare_source")
                            .arguments(Map.of("repositoryId", "orders", "requestId", originalRequest,
                                    "revision", revision)).build());
                    assertThat(accepted.isError()).isFalse();
                    String jobId = mapper.valueToTree(accepted.structuredContent()).get("jobId").asString();
                    HttpResponse<String> recovered = http(base, "/index/repositories/orders/jobs?requestId="
                            + originalRequest, "GET", "", "admin-secret");
                    assertThat(recovered.statusCode()).isEqualTo(200);
                    assertThat(mapper.readTree(recovered.body()).get("jobId").asString()).isEqualTo(jobId);
                    JsonNode byJob = mapper.valueToTree(client.callTool(McpSchema.CallToolRequest.builder("get_job")
                            .arguments(Map.of("repositoryId", "orders", "jobId", jobId)).build()).structuredContent());
                    assertThat(byJob.get("jobId").asString()).isEqualTo(jobId);
                    McpSchema.CallToolResult reused = client.callTool(McpSchema.CallToolRequest.builder("prepare_source")
                            .arguments(Map.of("repositoryId", "orders", "requestId", originalRequest)).build());
                    assertThat(reused.isError()).isTrue();
                    assertThat(mapper.valueToTree(reused.structuredContent()).get("code").asString())
                            .isEqualTo("REQUEST_ID_REUSED");
                    HttpResponse<String> duplicate = http(base, "/index/repositories/orders/source", "POST",
                            "{\"requestId\":\"" + originalRequest + "\"}", "admin-secret");
                    assertThat(duplicate.statusCode()).isEqualTo(409);
                    assertThat(mapper.readTree(duplicate.body())).isEqualTo(mapper.valueToTree(reused.structuredContent()));
                    String invalidRequest = UUID.randomUUID().toString();
                    HttpResponse<String> invalid = http(base, "/index/repositories/orders/source", "POST",
                            "{\"requestId\":\"" + invalidRequest + "\",\"obsolete\":true}", "admin-secret");
                    assertThat(invalid.statusCode()).isEqualTo(400);
                    assertThat(invalid.body()).doesNotContain(fixture.remote.toString(), fixture.admin.toString(), "admin-secret");
                    McpSchema.CallToolResult invalidTool = client.callTool(
                            McpSchema.CallToolRequest.builder("prepare_source")
                                    .arguments(Map.of("repositoryId", "orders", "requestId", invalidRequest,
                                            "obsolete", true)).build());
                    assertThat(invalidTool.isError()).isTrue();
                    assertThat(mapper.readTree(invalid.body()))
                            .isEqualTo(mapper.valueToTree(invalidTool.structuredContent()));
                }
            }
        }
    }

    @Test
    void invalid_json_envelopes_return_public_invalid_argument_instead_of_source_unavailable() throws Exception {
        try (LocalSourceFixture fixture = new LocalSourceFixture(root)) {
            try (ConfigurableApplicationContext context = fixture.start()) {
                String base = "http://127.0.0.1:"
                        + ((WebServerApplicationContext) context).getWebServer().getPort();
                for (String body : java.util.List.of("", "{", "null", "[]", "42")) {
                    HttpResponse<String> response = http(base, "/index/repositories/orders/source",
                            "POST", body, "admin-secret");
                    assertThat(response.statusCode()).as("HTTP status for JSON body %s", body).isEqualTo(400);
                    JsonNode failure = mapper.readTree(response.body());
                    assertThat(failure.get("code").asString()).isEqualTo("INVALID_ARGUMENT");
                    assertThat(failure.get("retryable").asBoolean()).isFalse();
                    assertThat(response.body()).doesNotContain(
                            fixture.remote.toString(), fixture.admin.toString(), "admin-secret");
                }
            }
        }
    }

    private HttpResponse<String> http(String base, String route, String method, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + route)).timeout(Duration.ofSeconds(10))
                .header("X-Api-Token", token);
        if (method.equals("POST")) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
