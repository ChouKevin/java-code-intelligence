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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real local-Git source and syntax-index transports. */
class IndexerPreparationTransportIT {
    @TempDir Path root;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void three_private_tools_share_exact_result_errors_security_and_lost_response_lookup() throws Exception {
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
                    List<McpSchema.Tool> tools = client.listTools().tools();
                    assertThat(tools).extracting(McpSchema.Tool::name)
                            .containsExactlyInAnyOrder("prepare_source", "get_job", "prepare_semantic_index");
                    String prematureRequest = UUID.randomUUID().toString();
                    HttpResponse<String> premature = http(base, "/index/repositories/orders/semantic-index", "POST",
                            "{\"requestId\":\"" + prematureRequest + "\",\"revision\":\"" + revision + "\"}",
                            "admin-secret");
                    assertThat(premature.statusCode()).isEqualTo(409);
                    JsonNode prerequisiteFailure = mapper.readTree(premature.body());
                    assertThat(prerequisiteFailure.get("code").asString()).isEqualTo("INDEX_NOT_CURRENT");
                    McpSchema.CallToolResult prematureTool = client.callTool(
                            McpSchema.CallToolRequest.builder("prepare_semantic_index")
                                    .arguments(Map.of("repositoryId", "orders", "requestId", prematureRequest,
                                            "revision", revision)).build());
                    assertThat(prematureTool.isError()).isTrue();
                    JsonNode prerequisiteToolFailure = mapper.valueToTree(prematureTool.structuredContent());
                    assertThat(prerequisiteToolFailure).isEqualTo(prerequisiteFailure);
                    assertThat(mapper.readTree(((McpSchema.TextContent) prematureTool.content().getFirst()).text()))
                            .isEqualTo(prerequisiteFailure);
                    JsonNode advertised = mapper.valueToTree(tools.stream()
                            .filter(tool -> tool.name().equals("prepare_semantic_index")).findFirst().orElseThrow());
                    List<String> mcpCodes = new ArrayList<>();
                    for (JsonNode branch : advertised.at("/outputSchema/oneOf")) {
                        for (JsonNode code : branch.at("/properties/code/enum")) mcpCodes.add(code.asString());
                    }
                    assertThat(mcpCodes).contains(prerequisiteFailure.get("code").asString());
                    YamlMapFactoryBean openApi = new YamlMapFactoryBean();
                    openApi.setResources(new ClassPathResource("openapi/semantic-indexer-api-v1.yaml"));
                    JsonNode httpSchema = mapper.valueToTree(openApi.getObject());
                    List<String> httpCodes = new ArrayList<>();
                    for (JsonNode code : httpSchema.at("/components/schemas/Failure/properties/code/enum"))
                        httpCodes.add(code.asString());
                    assertThat(httpCodes).contains(prerequisiteFailure.get("code").asString());
                    String originalRequest = UUID.randomUUID().toString();
                    McpSchema.CallToolResult accepted = client.callTool(McpSchema.CallToolRequest.builder("prepare_source")
                            .arguments(Map.of("repositoryId", "orders", "requestId", originalRequest)).build());
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
                            .arguments(Map.of("repositoryId", "orders", "requestId", originalRequest,
                                    "revision", revision)).build());
                    assertThat(reused.isError()).isTrue();
                    assertThat(mapper.valueToTree(reused.structuredContent()).get("code").asString())
                            .isEqualTo("REQUEST_ID_REUSED");
                    HttpResponse<String> duplicate = http(base, "/index/repositories/orders/source", "POST",
                            "{\"requestId\":\"" + originalRequest + "\",\"revision\":\"" + revision + "\"}", "admin-secret");
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

                    JsonNode completedSource = awaitCompleted(client, originalRequest);
                    assertThat(completedSource.get("operation").asString()).isEqualTo("prepare_source");
                    assertThat(completedSource.get("resolvedRevision").asString()).isEqualTo(revision);
                    String invalidSemanticRequest = UUID.randomUUID().toString();
                    String invalidSemanticBody = "{\"requestId\":\"" + invalidSemanticRequest
                            + "\",\"revision\":\"" + revision + "\",\"obsolete\":true}";
                    HttpResponse<String> invalidSemantic = http(base, "/index/repositories/orders/semantic-index",
                            "POST", invalidSemanticBody, "admin-secret");
                    assertThat(invalidSemantic.statusCode()).isEqualTo(400);
                    McpSchema.CallToolResult invalidSemanticTool = client.callTool(
                            McpSchema.CallToolRequest.builder("prepare_semantic_index")
                                    .arguments(Map.of("repositoryId", "orders", "requestId", invalidSemanticRequest,
                                            "revision", revision, "obsolete", true)).build());
                    assertThat(invalidSemanticTool.isError()).isTrue();
                    assertThat(mapper.readTree(invalidSemantic.body()))
                            .isEqualTo(mapper.valueToTree(invalidSemanticTool.structuredContent()));

                    String semanticRequest = UUID.randomUUID().toString();
                    HttpResponse<String> semanticAccepted = http(base, "/index/repositories/orders/semantic-index",
                            "POST", "{\"requestId\":\"" + semanticRequest + "\",\"revision\":\"" + revision + "\"}",
                            "admin-secret");
                    assertThat(semanticAccepted.statusCode()).isEqualTo(202);
                    JsonNode semanticJob = mapper.readTree(semanticAccepted.body());
                    assertThat(semanticJob.get("operation").asString()).isEqualTo("prepare_semantic_index");
                    assertThat(semanticJob.get("repositoryId").asString()).isEqualTo("orders");
                    assertThat(semanticJob.get("requestId").asString()).isEqualTo(semanticRequest);
                    assertThat(semanticJob.get("revision").asString()).isEqualTo(revision);
                    assertThat(semanticJob.get("indexGeneration").asString()).matches(
                            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
                    assertThat(semanticJob.get("phase").asString()).isNotEqualTo("READY");
                    assertThat(semanticAccepted.headers().firstValue("Location")).contains(
                            "/index/repositories/orders/jobs?jobId=" + semanticJob.get("jobId").asString());
                    assertThat(semanticAccepted.body()).doesNotContain(
                            fixture.remote.toString(), fixture.admin.toString(), "admin-secret");
                    McpSchema.CallToolResult semanticRecovery = client.callTool(
                            McpSchema.CallToolRequest.builder("get_job")
                                    .arguments(Map.of("repositoryId", "orders", "requestId", semanticRequest)).build());
                    assertThat(semanticRecovery.isError()).isFalse();
                    JsonNode recoveredSemanticJob = mapper.valueToTree(semanticRecovery.structuredContent());
                    assertThat(recoveredSemanticJob.get("operation").asString()).isEqualTo("prepare_semantic_index");
                    assertThat(recoveredSemanticJob.get("requestId").asString()).isEqualTo(semanticRequest);
                    assertThat(recoveredSemanticJob.get("revision").asString()).isEqualTo(revision);
                    assertThat(recoveredSemanticJob.get("jobId").asString())
                            .isEqualTo(semanticJob.get("jobId").asString());
                    assertThat(mapper.readTree(((McpSchema.TextContent) semanticRecovery.content().getFirst()).text()))
                            .isEqualTo(recoveredSemanticJob);
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

    private JsonNode awaitCompleted(McpSyncClient client, String requestId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("get_job")
                    .arguments(Map.of("repositoryId", "orders", "requestId", requestId)).build());
            assertThat(result.isError()).isFalse();
            JsonNode job = mapper.valueToTree(result.structuredContent());
            String phase = job.get("phase").asString();
            if (phase.equals("COMPLETE")) {
                return job;
            }
            assertThat(phase).withFailMessage("source job failed with code %s", job.get("failureCode")).isNotEqualTo("FAILED");
            Thread.sleep(50);
        }
        throw new AssertionError("source job did not complete before the test deadline");
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
