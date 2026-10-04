package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.TextSearchRequest;
import com.java.semantic.model.source.SourceReadContract.TextSearchResult;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.source.SourceQueryException.Code;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

class SourceTransportErrorParityTest {
    @TempDir Path temp;

    @Test
    void every_safe_source_error_remains_identical_across_http_and_mcp() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        for (Map.Entry<Code, Integer> caseUnderTest : Map.of(
                Code.SOURCE_NOT_PREPARED, 409, Code.SOURCE_UNSUPPORTED, 422, Code.SOURCE_BUSY, 503,
                Code.SOURCE_UNAVAILABLE, 503, Code.SOURCE_TIMEOUT, 504).entrySet()) {
            Code code = caseUnderTest.getKey();
            RepositorySourcePort failing = new RepositorySourcePort() {
                @Override public FileCollection listFiles(AdmittedSourceRevision revision, FileListRequest request) {
                    throw new SourceQueryException(code, new IllegalStateException("/private/credentials"));
                }
                @Override public TextSearchResult searchText(AdmittedSourceRevision revision, TextSearchRequest request) {
                    throw new SourceQueryException(code, new IllegalStateException("/private/credentials"));
                }
                @Override public SourceResult readSource(AdmittedSourceRevision revision, ReadSourceRequest request) {
                    throw new SourceQueryException(code, new IllegalStateException("/private/credentials"));
                }
            };
            SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), failing);
            MockMvc http = standaloneSetup(new SemanticQueryController(facade))
                    .setControllerAdvice(new QueryApiExceptionHandler())
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
            Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample", "revision",
                    SourceFilesystemFixture.SHA), "path", "Order.java");
            String payload = http.perform(post("/api/v1/source").contentType("application/json")
                    .content(fixture.mapper.writeValueAsBytes(input))).andExpect(status().is(caseUnderTest.getValue()))
                    .andReturn().getResponse().getContentAsString();
            McpStatelessServerFeatures.SyncToolSpecification tool = new QueryMcpToolCatalogConfiguration()
                    .mcpQueryToolSpecifications(facade, fixture.mapper).stream()
                    .filter(value -> value.tool().name().equals("read_source")).findFirst().orElseThrow();
            McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                    McpSchema.CallToolRequest.builder("read_source").arguments(input).build());
            JsonNode httpJson = fixture.mapper.readTree(payload);
            assertThat(httpJson).isEqualTo(fixture.mapper.readTree(fixture.mapper.writeValueAsString(mcp.structuredContent())));
            assertThat(fixture.mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text()))
                    .isEqualTo(httpJson);
            assertThat(mcp.isError()).isTrue();
            assertThat(httpJson.get("code").asText()).isEqualTo(code.name());
            assertThat(payload).doesNotContain("private", "credentials");
        }
    }
}
