package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.source.SourceReadContract.FileCollection;
import com.java.semantic.model.source.SourceReadContract.FileListRequest;
import com.java.semantic.model.source.SourceReadContract.FileFindRequest;
import com.java.semantic.model.source.SourceReadContract.FileFindResult;
import com.java.semantic.model.source.SourceReadContract.ReadSourceRequest;
import com.java.semantic.model.source.SourceReadContract.SemanticStatus;
import com.java.semantic.model.source.SourceReadContract.SourceResult;
import com.java.semantic.model.source.SourceReadContract.TextSearchRequest;
import com.java.semantic.model.source.SourceReadContract.TextSearchResult;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.index.RepositoryIndexPort;
import com.java.semantic.query.source.SourceQueryException.Code;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
                @Override public FileFindResult findFiles(AdmittedSourceRevision revision, FileFindRequest request) {
                    throw new SourceQueryException(code, new IllegalStateException("/private/credentials"));
                }
                @Override public TextSearchResult searchText(AdmittedSourceRevision revision, TextSearchRequest request) {
                    throw new SourceQueryException(code, new IllegalStateException("/private/credentials"));
                }
                @Override public SourceResult readSource(AdmittedSourceRevision revision, ReadSourceRequest request,
                        Function<Optional<String>, IndexContract.FoldResult> folds) {
                    throw new SourceQueryException(code, new IllegalStateException("/private/credentials"));
                }
            };
            SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), failing, fixture.index,
                    fixture.properties.maxActiveSearches());
            MockMvc http = standaloneSetup(new SemanticQueryController(facade))
                    .setControllerAdvice(new QueryApiExceptionHandler())
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
            for (String operation : java.util.List.of("read_source", "find_files")) {
                Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample", "revision",
                        SourceFilesystemFixture.SHA), operation.equals("read_source") ? "path" : "filePattern", "Order.java");
                String route = operation.equals("read_source") ? "/api/v1/source" : "/api/v1/find-files";
                String payload = http.perform(post(route).contentType("application/json")
                        .content(fixture.mapper.writeValueAsBytes(input))).andExpect(status().is(caseUnderTest.getValue()))
                        .andReturn().getResponse().getContentAsString();
                McpServerFeatures.SyncToolSpecification tool = new QueryMcpToolCatalogConfiguration()
                        .mcpQueryToolSpecifications(facade, fixture.mapper).stream()
                        .filter(value -> value.tool().name().equals(operation)).findFirst().orElseThrow();
                McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                        McpSchema.CallToolRequest.builder(operation).arguments(input).build());
            JsonNode httpJson = fixture.mapper.readTree(payload);
            assertThat(httpJson).isEqualTo(fixture.mapper.readTree(fixture.mapper.writeValueAsString(mcp.structuredContent())));
            assertThat(fixture.mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text()))
                    .isEqualTo(httpJson);
            assertThat(mcp.isError()).isTrue();
            assertThat(httpJson.get("code").asString()).isEqualTo(code.name());
            assertThat(payload).doesNotContain("private", "credentials");
            }
        }
    }

    @Test
    void every_index_failure_uses_fixed_advice_and_matching_http_status_and_mcp_error() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        for (Map.Entry<IndexFailure.Code, Integer> caseUnderTest : Map.of(
                IndexFailure.Code.INDEX_NOT_PREPARED, 409,
                IndexFailure.Code.INDEX_NOT_CURRENT, 409,
                IndexFailure.Code.INDEX_INCOMPATIBLE, 409,
                IndexFailure.Code.INDEX_GENERATION_EXPIRED, 404,
                IndexFailure.Code.INDEX_RESULT_TOO_LARGE, 413,
                IndexFailure.Code.INDEX_UNAVAILABLE, 503,
                IndexFailure.Code.INDEX_BUSY, 503,
                IndexFailure.Code.INDEX_CAPABILITY_UNAVAILABLE, 409,
                IndexFailure.Code.INDEX_SYMBOL_NOT_FOUND, 404,
                IndexFailure.Code.INDEX_TIMEOUT, 504).entrySet()) {
            IndexFailure.Code code = caseUnderTest.getKey();
            String generation = UUID.randomUUID().toString();
            IndexContract.Coverage coverage = new IndexContract.Coverage(0, 0, 0, 0, 0, 0, true);
            RepositoryIndexPort failing = new RepositoryIndexPort() {
                @Override
                public IndexContract.Discovery discover(AdmittedSourceRevision revision) {
                    return new IndexContract.Discovery(SemanticStatus.READY,
                            Optional.of(new IndexContract.IndexSummary(generation, IndexContract.syntaxProfile(), coverage, IndexContract.capabilities(IndexContract.syntaxProfile()))));
                }

                @Override
                public IndexContract.Result<IndexContract.Declaration> searchCode(AdmittedSourceRevision revision,
                        IndexContract.SearchCodeRequest request) {
                    throw new IndexFailure(code, new IllegalStateException("/private/index/path"));
                }

                @Override
                public IndexContract.Result<IndexContract.Declaration> outline(AdmittedSourceRevision revision,
                        IndexContract.OutlineRequest request) {
                    throw new IndexFailure(code, new IllegalStateException("/private/index/path"));
                }

                @Override
                public IndexContract.Result<IndexContract.EntryCandidate> entryPoints(AdmittedSourceRevision revision,
                        IndexContract.EntryPointsRequest request) {
                    throw new IndexFailure(code, new IllegalStateException("/private/index/path"));
                }

                @Override
                public com.java.semantic.model.index.RelationContract.Result findRelations(AdmittedSourceRevision revision,
                        com.java.semantic.model.index.RelationContract.Request request) {
                    throw new IndexFailure(code, new IllegalStateException("/private/index/path"));
                }

                @Override
                public IndexContract.FoldResult folds(AdmittedSourceRevision revision, String path,
                        Optional<String> generation) {
                    throw new IndexFailure(code, new IllegalStateException("/private/index/path"));
                }
            };
            SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), failing,
                    fixture.properties.maxActiveSearches());
            MockMvc http = standaloneSetup(new SemanticQueryController(facade))
                    .setControllerAdvice(new QueryApiExceptionHandler())
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
            Map<String, Object> input = Map.of("context", Map.of("repositoryId", "sample", "revision",
                    SourceFilesystemFixture.SHA), "indexGeneration", generation,
                    "anchor", Map.of("kind", "FILE", "path", "Order.java"), "direction", "OUTGOING");
            String payload = http.perform(post("/api/v1/relations").contentType("application/json")
                    .content(fixture.mapper.writeValueAsBytes(input))).andExpect(status().is(caseUnderTest.getValue()))
                    .andReturn().getResponse().getContentAsString();
            McpServerFeatures.SyncToolSpecification tool = new QueryMcpToolCatalogConfiguration()
                    .mcpQueryToolSpecifications(facade, fixture.mapper).stream()
                    .filter(value -> value.tool().name().equals("find_relations")).findFirst().orElseThrow();
            McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                    McpSchema.CallToolRequest.builder("find_relations").arguments(input).build());
            JsonNode httpJson = fixture.mapper.readTree(payload);
            assertThat(httpJson).isEqualTo(fixture.mapper.readTree(fixture.mapper.writeValueAsString(mcp.structuredContent())));
            assertThat(fixture.mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text()))
                    .isEqualTo(httpJson);
            assertThat(mcp.isError()).isTrue();
            assertThat(httpJson.get("code").asString()).isEqualTo(code.name());
            assertThat(httpJson.get("message").asString()).isEqualTo(code.advice());
            assertThat(payload).doesNotContain("private", "index/path");
            Map<String, Object> syntaxInput = Map.of("context", Map.of("repositoryId", "sample", "revision",
                    SourceFilesystemFixture.SHA), "query", "Order", "match", "exact");
            String syntaxPayload = http.perform(post("/api/v1/search-code").contentType("application/json")
                    .content(fixture.mapper.writeValueAsBytes(syntaxInput))).andExpect(status().is(caseUnderTest.getValue()))
                    .andReturn().getResponse().getContentAsString();
            McpServerFeatures.SyncToolSpecification syntaxTool = new QueryMcpToolCatalogConfiguration()
                    .mcpQueryToolSpecifications(facade, fixture.mapper).stream()
                    .filter(value -> value.tool().name().equals("search_code")).findFirst().orElseThrow();
            McpSchema.CallToolResult syntaxMcp = syntaxTool.callHandler().apply(null,
                    McpSchema.CallToolRequest.builder("search_code").arguments(syntaxInput).build());
            assertThat(fixture.mapper.readTree(syntaxPayload)).isEqualTo(httpJson);
            assertThat(fixture.mapper.readTree(fixture.mapper.writeValueAsString(syntaxMcp.structuredContent()))).isEqualTo(httpJson);
            assertThat(fixture.mapper.readTree(((McpSchema.TextContent) syntaxMcp.content().getFirst()).text())).isEqualTo(httpJson);
            assertThat(syntaxMcp.isError()).isTrue();
        }
    }

    @Test
    void unavailable_summary_reports_safe_reason_identically_and_leaves_raw_reading_available() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Order.java", "class Order {}\n");
        fixture.publish(Optional.empty());
        SemanticQueryFacade facade = new SemanticQueryFacade(fixture.catalog(), fixture.service(), fixture.index,
                fixture.properties.maxActiveSearches());
        MockMvc http = standaloneSetup(new SemanticQueryController(facade))
                .setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(fixture.mapper)).build();
        Map<String, Object> context = Map.of("repositoryId", "sample", "revision", SourceFilesystemFixture.SHA);
        Map<String, Object> input = Map.of("context", context, "path", "Order.java", "view", "summary");
        String payload = http.perform(post("/api/v1/source").contentType("application/json")
                .content(fixture.mapper.writeValueAsBytes(input))).andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        McpServerFeatures.SyncToolSpecification tool = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, fixture.mapper).stream()
                .filter(value -> value.tool().name().equals("read_source")).findFirst().orElseThrow();
        McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                McpSchema.CallToolRequest.builder("read_source").arguments(input).build());
        JsonNode httpJson = fixture.mapper.readTree(payload);
        assertThat(mcp.isError()).isTrue();
        assertThat(httpJson.get("code").asString()).isEqualTo("SOURCE_SUMMARY_UNAVAILABLE");
        assertThat(httpJson.get("reason").asString()).isEqualTo("INDEX_NOT_READY");
        JsonNode structured = fixture.mapper.valueToTree(mcp.structuredContent());
        assertThat(structured).isEqualTo(httpJson);
        assertThat(fixture.mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text())).isEqualTo(httpJson);
        String raw = http.perform(post("/api/v1/source").contentType("application/json")
                .content(fixture.mapper.writeValueAsBytes(Map.of("context", context, "path", "Order.java", "view", "raw"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        SourceResult result = fixture.mapper.readValue(raw, SourceResult.class);
        assertThat(result.segments()).singleElement().satisfies(segment ->
                assertThat(segment.content()).contains("class Order {}\n"));
    }
}
