package com.java.semantic.api;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.query.application.CodeFactKindMismatchException;
import com.java.semantic.query.application.RevisionOutdatedException;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
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
                .callHandler().apply(null, new McpSchema.CallToolRequest(toolName, arguments));
    }
}
