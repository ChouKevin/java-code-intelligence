package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.mcp.SessionOwnedMcpTransport;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.FileFindResult;
import com.java.semantic.model.source.SourceReadContract.FoundFile;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

class SourceFindResponseBudgetTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void byteLimitedPagesPreserveEveryPathAndCompleteDuplicatedWireBudget(int idKind) throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String prefix = String.join("/", Collections.nCopies(32, "a".repeat(80) + "中😀\""));
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String path = prefix + "/file-%03d.dat".formatted(index);
            fixture.metadata(path, EntryStatus.BINARY, index); expected.add(path);
        }
        fixture.metadata("\uE000.dat", EntryStatus.BINARY, 3); expected.add("\uE000.dat");
        fixture.metadata("😀.dat", EntryStatus.BINARY, 4); expected.add("😀.dat");
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index, 2);
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
        QueryMcpToolCatalogConfiguration configuration = new QueryMcpToolCatalogConfiguration();
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(fixture.mapper)).mcpEndpoint("/mcp").build();
        McpSyncServer server = configuration.queryMcpServer(new SessionOwnedMcpTransport(transport), new McpServerProperties(),
                configuration.mcpQueryToolSpecifications(facade, fixture.mapper));
        McpWireTestClient client = new McpWireTestClient(transport, fixture.mapper);
        Object requestId = switch (idKind) { case 0 -> 2; case 1 -> "x".repeat(510); default -> "\u0001".repeat(83) + "中😀abcde"; };
        try {
            List<String> actual = new ArrayList<>(); Optional<String> cursor = Optional.empty(); int pages = 0;
            do {
                Map<String, Object> request = new HashMap<>(Map.of("context", fixture.context, "filePattern", "**", "limit", 100));
                cursor.ifPresent(value -> request.put("cursor", value));
                String body = http.perform(post("/api/v1/find-files").contentType("application/json")
                        .content(fixture.mapper.writeValueAsBytes(request))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(524_288);
                McpWireTestClient.Response response = client.request(fixture.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", requestId,
                        "method", "tools/call", "params", Map.of("name", "find_files", "arguments", request))));
                assertThat(response.status()).isEqualTo(200);
                String wire = McpWireTestClient.jsonBody(response.body());
                assertThat(wire.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(524_288);
                JsonNode envelope = fixture.mapper.readTree(wire); JsonNode result = envelope.get("result");
                assertThat(envelope.get("id")).isEqualTo(fixture.mapper.valueToTree(requestId));
                assertThat(result.get("isError").asBoolean()).isFalse();
                JsonNode httpJson = fixture.mapper.readTree(body);
                assertThat(result.get("structuredContent")).isEqualTo(httpJson);
                assertThat(fixture.mapper.readTree(result.get("content").get(0).get("text").asString())).isEqualTo(httpJson);
                FileFindResult page = fixture.mapper.treeToValue(httpJson, FileFindResult.class);
                assertThat(page.context()).isEqualTo(fixture.context);
                assertThat(page.page().returned()).isEqualTo(page.items().size());
                actual.addAll(page.items().stream().map(FoundFile::path).toList()); cursor = page.page().nextCursor();
                assertThat(++pages).isLessThan(10);
            } while (cursor.isPresent());
            assertThat(pages).isGreaterThan(1);
            assertThat(actual).containsExactlyElementsOf(expected);
        } finally { client.delete(); server.close(); }
    }

    @Test
    void a_complete_first_row_that_cannot_carry_its_continuation_fails_without_empty_cursor_loop() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        String prefix = String.join("/", Collections.nCopies(320, "x".repeat(80)));
        fixture.metadata(prefix + "/a.dat", EntryStatus.BINARY, 1);
        fixture.metadata(prefix + "/b.dat", EntryStatus.BINARY, 2);
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index, 2);
        MockMvc http = standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
        Map<String, Object> input = Map.of("context", Map.of("repositoryId", fixture.context.repositoryId(),
                "revision", fixture.context.revision()), "filePattern", "**", "limit", 1);
        String body = http.perform(post("/api/v1/find-files").contentType("application/json").content(fixture.mapper.writeValueAsBytes(input)))
                .andExpect(status().is(413)).andReturn().getResponse().getContentAsString();
        assertThat(fixture.mapper.readTree(body).get("code").asString()).isEqualTo("SOURCE_RESULT_TOO_LARGE");
        io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, fixture.mapper).stream().filter(value -> value.tool().name().equals("find_files")).findFirst().orElseThrow();
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = tool.callHandler().apply(null,
                io.modelcontextprotocol.spec.McpSchema.CallToolRequest.builder("find_files").arguments(input).build());
        assertThat(result.isError()).isTrue();
        JsonNode error = fixture.mapper.valueToTree(result.structuredContent());
        assertThat(error).isEqualTo(fixture.mapper.readTree(body));
    }
}
