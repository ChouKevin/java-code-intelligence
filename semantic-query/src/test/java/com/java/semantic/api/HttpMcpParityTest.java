package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.CodeFactKindMismatchException;
import com.java.semantic.query.application.GitEvidenceNotFoundException;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HttpMcpParityTest {

    private static final String REPOSITORY_ID = "orders";
    private static final String REVISION = "a".repeat(40);
    private static final String FACT_ID = "b".repeat(64);

    @Test
    void authenticated_http_and_mcp_return_the_same_success_and_revision_outdated_application_bodies() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        SemanticQueryContract.RepositoryCollection repositories = new SemanticQueryContract.RepositoryCollection(
                List.of(new SemanticQueryContract.RepositoryItem(REPOSITORY_ID, REVISION)),
                new SemanticQueryContract.Page(0, 20, 1, 1, false));
        when(facade.listRepositories(any())).thenReturn(repositories);
        RevisionOutdatedException outdated = new RevisionOutdatedException(RepositoryId.of(REPOSITORY_ID),
                new RepositoryRevision(REVISION), new RepositoryRevision("b".repeat(40)));
        when(facade.searchCode(any())).thenThrow(outdated);

        MockMvc http = authenticatedHttp(facade);
        String httpSuccess = http.perform(get("/api/v1/repositories").header(QueryTokenFilter.TOKEN_HEADER, "query-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String httpFailure = http.perform(post("/api/v1/search-code").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                .contentType("application/json").content("""
                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","query":"payment"}
                """))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

        ObjectMapper mapper = applicationJsonMapper();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, mapper);
        McpSchema.CallToolResult mcpSuccess = call(specifications, "list_repositories", Map.of());
        McpSchema.CallToolResult mcpFailure = call(specifications, "search_code", Map.of(
                "repositoryId", "orders", "revision", "a".repeat(40), "query", "payment"));

        assertThat(mapper.readTree(httpSuccess)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpSuccess.structuredContent())));
        assertThat(mapper.readTree(httpFailure)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpFailure.structuredContent())));
    }

    @Test
    void authenticated_http_and_mcp_return_the_same_compact_source_coverage_for_empty_searches() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        SemanticQueryContract.SearchCodeResult searchResult = new SemanticQueryContract.SearchCodeResult(REPOSITORY_ID, REVISION, List.of(),
                new SemanticQueryContract.Page(0, 20, 0, 0, false),
                new SemanticQueryContract.SourceCoverage(3, 2, List.of("PARSE_ERROR", "UNRESOLVED_TYPE")));
        when(facade.searchCode(any())).thenReturn(searchResult);

        String httpSuccess = authenticatedHttp(facade).perform(post("/api/v1/search-code")
                        .header(QueryTokenFilter.TOKEN_HEADER, "query-token").contentType("application/json").content("""
                                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","query":"missing"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ObjectMapper mapper = applicationJsonMapper();
        McpSchema.CallToolResult mcpSuccess = call(new QueryMcpToolCatalogConfiguration().mcpQueryToolSpecifications(facade, mapper),
                "search_code", Map.of("repositoryId", REPOSITORY_ID, "revision", REVISION, "query", "missing"));

        assertThat(mapper.readTree(httpSuccess)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpSuccess.structuredContent())));
        assertThat(httpSuccess).contains("\"sourceCoverage\":{\"indexedSourceCount\":3,\"issueCount\":2,\"issueCodes\":[\"PARSE_ERROR\",\"UNRESOLVED_TYPE\"]}")
                .doesNotContain("sourcePath", "issues");
    }

    @Test
    void git_snapshot_operations_dispatch_the_same_defaulted_facade_requests_and_structured_bodies_over_http_and_mcp() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        String snapshotId = "c".repeat(36);
        SemanticQueryContract.GitSnapshotCoverage coverage = new SemanticQueryContract.GitSnapshotCoverage(1, 1, 0, 0, 0, 0, 0, 0, 0);
        SemanticQueryContract.GitFileCollection files = new SemanticQueryContract.GitFileCollection(REPOSITORY_ID, snapshotId, REVISION,
                List.of(new SemanticQueryContract.GitFileItem("src/Evidence.java", "src/Evidence.java", "BLOB", 17, "TEXT")),
                new SemanticQueryContract.Page(0, 20, 1, 1, false), coverage);
        SemanticQueryContract.GitFileContent file = new SemanticQueryContract.GitFileContent(REPOSITORY_ID, snapshotId, REVISION,
                "src/Evidence.java", "src/Evidence.java", "TEXT", "class Evidence { }\n", 1, 1, true, true, java.util.Optional.empty());
        SemanticQueryContract.GitChangeItem change = new SemanticQueryContract.GitChangeItem("change-1", "MODIFY", "src/Evidence.java",
                "src/Evidence.java", "100644", "100644", "c".repeat(40), "d".repeat(40), "TEXT");
        SemanticQueryContract.GitFileDiffResult diff = new SemanticQueryContract.GitFileDiffResult(REPOSITORY_ID, "comparison-1",
                "b".repeat(40), REVISION, change, "diff --git a/src/Evidence.java b/src/Evidence.java\n", java.util.Optional.empty());
        SemanticQueryContract.GitTextSearchResult search = new SemanticQueryContract.GitTextSearchResult(REPOSITORY_ID, snapshotId, REVISION,
                List.of(new SemanticQueryContract.GitTextMatch("src/Evidence.java", "src/Evidence.java", 1, 7, "class Evidence { }", false)),
                true, java.util.Optional.empty(), coverage);
        when(facade.listFiles(any())).thenReturn(files);
        when(facade.getFileDiff(any())).thenReturn(diff);
        when(facade.readFile(any())).thenReturn(file);
        when(facade.searchText(any())).thenReturn(search);

        MockMvc http = authenticatedHttp(facade);
        ObjectMapper mapper = applicationJsonMapper();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, mapper);
        String filesHttp = http.perform(post("/api/v1/git/files").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content("{\"repositoryId\":\"orders\",\"snapshotId\":\"" + snapshotId
                                + "\",\"revision\":\"" + REVISION + "\",\"directory\":\"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        McpSchema.CallToolResult filesMcp = call(specifications, "list_files", Map.of("repositoryId", REPOSITORY_ID,
                "snapshotId", snapshotId, "revision", REVISION, "directory", ""));
        String diffHttp = http.perform(post("/api/v1/git/file-diff").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content("{\"repositoryId\":\"orders\",\"comparisonId\":\"comparison-1\",\"previous\":\""
                                + "b".repeat(40) + "\",\"current\":\"" + REVISION + "\",\"changeId\":\"change-1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        McpSchema.CallToolResult diffMcp = call(specifications, "get_file_diff", Map.of("repositoryId", REPOSITORY_ID,
                "comparisonId", "comparison-1", "previous", "b".repeat(40), "current", REVISION, "changeId", "change-1"));
        String fileHttp = http.perform(post("/api/v1/git/file").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content("{\"repositoryId\":\"orders\",\"snapshotId\":\"" + snapshotId
                                + "\",\"revision\":\"" + REVISION + "\",\"path\":\"src/Evidence.java\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        McpSchema.CallToolResult fileMcp = call(specifications, "read_file", Map.of("repositoryId", REPOSITORY_ID,
                "snapshotId", snapshotId, "revision", REVISION, "path", "src/Evidence.java"));
        String searchHttp = http.perform(post("/api/v1/git/search").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content("{\"repositoryId\":\"orders\",\"snapshotId\":\"" + snapshotId
                                + "\",\"revision\":\"" + REVISION + "\",\"query\":\"Evidence\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        McpSchema.CallToolResult searchMcp = call(specifications, "search_text", Map.of("repositoryId", REPOSITORY_ID,
                "snapshotId", snapshotId, "revision", REVISION, "query", "Evidence"));

        assertThat(mapper.readTree(filesHttp)).isEqualTo(mapper.readTree(mapper.writeValueAsString(filesMcp.structuredContent())));
        assertThat(mapper.readTree(diffHttp)).isEqualTo(mapper.readTree(mapper.writeValueAsString(diffMcp.structuredContent())));
        assertThat(mapper.readTree(fileHttp)).isEqualTo(mapper.readTree(mapper.writeValueAsString(fileMcp.structuredContent())));
        assertThat(mapper.readTree(searchHttp)).isEqualTo(mapper.readTree(mapper.writeValueAsString(searchMcp.structuredContent())));
        assertThat(diffHttp).doesNotContain("nextCursor");
        assertThat(fileHttp).doesNotContain("nextCursor");
        assertThat(searchHttp).doesNotContain("nextCursor");
        ArgumentCaptor<SemanticQueryContract.GitFileListRequest> filesRequest = ArgumentCaptor.forClass(SemanticQueryContract.GitFileListRequest.class);
        verify(facade, times(2)).listFiles(filesRequest.capture());
        assertThat(filesRequest.getAllValues()).allSatisfy(request -> {
            assertThat(request.directory()).isEmpty();
            assertThat(request.offset()).isZero();
            assertThat(request.limit()).isEqualTo(SemanticQueryContract.DEFAULT_LIMIT);
        });
        ArgumentCaptor<SemanticQueryContract.GitFileReadRequest> fileRequest = ArgumentCaptor.forClass(SemanticQueryContract.GitFileReadRequest.class);
        verify(facade, times(2)).readFile(fileRequest.capture());
        assertThat(fileRequest.getAllValues()).allSatisfy(request -> {
            assertThat(request.startLine()).isEmpty();
            assertThat(request.maxLines()).isEqualTo(SemanticQueryContract.DEFAULT_FILE_LINES);
            assertThat(request.cursor()).isEmpty();
        });
        ArgumentCaptor<SemanticQueryContract.GitTextSearchRequest> searchRequest = ArgumentCaptor.forClass(SemanticQueryContract.GitTextSearchRequest.class);
        verify(facade, times(2)).searchText(searchRequest.capture());
        assertThat(searchRequest.getAllValues()).allSatisfy(request -> {
            assertThat(request.directory()).isEmpty();
            assertThat(request.cursor()).isEmpty();
            assertThat(request.limit()).isEqualTo(SemanticQueryContract.DEFAULT_LIMIT);
        });

        when(facade.searchText(any())).thenThrow(new GitEvidenceNotFoundException());
        String searchFailureHttp = http.perform(post("/api/v1/git/search").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content("{\"repositoryId\":\"orders\",\"snapshotId\":\"" + snapshotId
                                + "\",\"revision\":\"" + REVISION + "\",\"query\":\"Evidence\"}"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        McpSchema.CallToolResult searchFailureMcp = call(specifications, "search_text", Map.of("repositoryId", REPOSITORY_ID,
                "snapshotId", snapshotId, "revision", REVISION, "query", "Evidence"));
        assertThat(mapper.readTree(searchFailureHttp)).isEqualTo(mapper.readTree(mapper.writeValueAsString(searchFailureMcp.structuredContent())));
        assertThat(searchFailureMcp.isError()).isTrue();
    }

    @Test
    void authenticated_http_rejects_an_unknown_legacy_field_with_the_shared_invalid_argument_error() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        when(facade.searchCode(any())).thenReturn(emptyCollection());

        authenticatedHttp(facade).perform(post("/api/v1/search-code").header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content("""
                                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","query":"payment","legacySource":true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"INVALID_ARGUMENT\""));
    }

    @Test
    void blank_package_prefix_has_the_same_invalid_argument_body_for_authenticated_http_and_mcp() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        when(facade.searchCode(any())).thenReturn(emptyCollection());

        String httpFailure = authenticatedHttp(facade).perform(post("/api/v1/search-code")
                        .header(QueryTokenFilter.TOKEN_HEADER, "query-token").contentType("application/json").content("""
                                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","query":"payment","packagePrefix":" "}
                                """))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        ObjectMapper mapper = applicationJsonMapper();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, mapper);
        McpSchema.CallToolResult mcpFailure = call(specifications, "search_code", Map.of(
                "repositoryId", REPOSITORY_ID, "revision", REVISION, "query", "payment", "packagePrefix", " "));

        assertThat(mapper.readTree(httpFailure)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpFailure.structuredContent())));
    }

    @Test
    void authenticated_http_and_mcp_report_fact_kind_mismatch_for_known_wrong_kind_method_fact_ids() throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);
        when(facade.findCallers(any())).thenThrow(new CodeFactKindMismatchException());

        String httpFailure = authenticatedHttp(facade).perform(post("/api/v1/callers")
                        .header(QueryTokenFilter.TOKEN_HEADER, "query-token").contentType("application/json").content("""
                                {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","methodFactId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                                """))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        ObjectMapper mapper = applicationJsonMapper();
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = new QueryMcpToolCatalogConfiguration()
                .mcpQueryToolSpecifications(facade, mapper);
        McpSchema.CallToolResult mcpFailure = call(specifications, "find_callers", Map.of(
                "repositoryId", REPOSITORY_ID, "revision", REVISION, "methodFactId", FACT_ID));

        assertThat(mapper.readTree(httpFailure)).isEqualTo(mapper.readTree(mapper.writeValueAsString(mcpFailure.structuredContent())));
        assertThat(httpFailure).contains("\"code\":\"FACT_KIND_MISMATCH\"");
    }

    @ParameterizedTest
    @MethodSource("requestsWithNullKindElements")
    void authenticated_http_rejects_null_kind_elements_as_shared_invalid_arguments(String path, String payload) throws Exception {
        SemanticQueryFacade facade = mock(SemanticQueryFacade.class);

        authenticatedHttp(facade).perform(post(path).header(QueryTokenFilter.TOKEN_HEADER, "query-token")
                        .contentType("application/json").content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("\"code\":\"INVALID_ARGUMENT\""));
    }

    private static Stream<Arguments> requestsWithNullKindElements() {
        return Stream.of(
                Arguments.of("/api/v1/search-code", """
                        {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","query":"payment","kinds":[null]}
                        """),
                Arguments.of("/api/v1/entry-points", """
                        {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","kinds":[null]}
                        """),
                Arguments.of("/api/v1/type-members", """
                        {"repositoryId":"orders","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","typeFactId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","kinds":[null]}
                        """));
    }

    private static SemanticQueryContract.SearchCodeResult emptyCollection() {
        return new SemanticQueryContract.SearchCodeResult(REPOSITORY_ID, REVISION, List.of(),
                new SemanticQueryContract.Page(0, 20, 0, 0, false), new SemanticQueryContract.SourceCoverage(0, 0, List.of()));
    }

    private static MockMvc authenticatedHttp(SemanticQueryFacade facade) {
        QuerySecurityProperties securityProperties = new QuerySecurityProperties();
        securityProperties.setApiToken("query-token");
        return standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(applicationJsonMapper()))
                .addFilters(new QueryTokenFilter(securityProperties)).build();
    }

    private static JsonMapper applicationJsonMapper() {
        AtomicReference<JsonMapper> mapper = new AtomicReference<>();
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper.set(context.getBean(JsonMapper.class)));
        return Objects.requireNonNull(mapper.get(), "application JSON mapper is required");
    }

    private static McpSchema.CallToolResult call(List<McpStatelessServerFeatures.SyncToolSpecification> specifications,
                                                  String toolName, Map<String, Object> arguments) {
        return specifications.stream().filter(specification -> toolName.equals(specification.tool().name())).findFirst().orElseThrow()
                .callHandler().apply(null, McpSchema.CallToolRequest.builder(toolName).arguments(arguments).build());
    }
}
