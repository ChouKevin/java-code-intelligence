package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisMetadata;
import com.java.semantic.callgraph.domain.AnalysisWarning;
import com.java.semantic.callgraph.domain.CallEdge;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;
import com.java.semantic.callgraph.domain.FlattenedCallGraph;
import com.java.semantic.callgraph.domain.MethodId;
import com.java.semantic.callgraph.domain.RevisionBoundAnalysisResult;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.semantic.domain.SemanticAmbiguousMethodException;
import com.java.semantic.semantic.domain.SemanticEngineNotReadyException;
import com.java.semantic.semantic.domain.SemanticEngineStartFailedException;
import com.java.semantic.semantic.domain.SemanticProtocolException;
import com.java.semantic.semantic.domain.SemanticRequestTimeoutException;
import com.java.semantic.semantic.domain.SemanticSymbolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class AnalysisControllerTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final String VALID_REQUEST = """
            {
              "repoId":"orders",
              "packageName":"com.acme",
              "className":"OrderService",
              "methodSignature":"place(Order)"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticAnalysisApplicationService semanticAnalysisApplicationService;

    private ExplainableCallGraph graph;
    private AnalysisMetadata metadata;

    @BeforeEach
    void setUp() {
        MethodId root = new MethodId(
                "orders", "com.acme", "OrderService", "place", List.of("Order"));
        graph = new ExplainableCallGraph(
                root,
                List.of(),
                List.<CallEdge>of(),
                Map.of(),
                new FlattenedCallGraph(List.of(), "place(Order)", Map.of()));
        metadata = new AnalysisMetadata("orders", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void should_return_revision_bound_explainable_analysis() throws Exception {
        given(semanticAnalysisApplicationService.analyze(
                REPOSITORY_ID,
                Optional.of(REVISION),
                "com.acme",
                "OrderService",
                "place(Order)"))
                .willReturn(RevisionBoundAnalysisResult.success(graph, metadata, REVISION.value()));

        mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "packageName":"com.acme",
                                  "className":"OrderService",
                                  "methodSignature":"place(Order)",
                                  "expectedRevision":"1111111111111111111111111111111111111111"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION.value()))
                .andExpect(jsonPath("$.data.root.methodName").value("place"));
    }

    @Test
    void should_return_partial_analysis_with_safe_warnings_and_errors() throws Exception {
        AnalysisWarning warning = new AnalysisWarning("W1", "warning", "safe-location");
        AnalysisError error = new AnalysisError("E1", "error", "safe-detail");
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willReturn(RevisionBoundAnalysisResult.partial(
                        graph, List.of(warning), List.of(error), metadata, REVISION.value()));

        mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.warnings[0].code").value("W1"))
                .andExpect(jsonPath("$.errors[0].code").value("E1"))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION.value()));
    }

    @Test
    void should_return_failed_analysis_with_null_data() throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willReturn(RevisionBoundAnalysisResult.failed(
                        List.of(new AnalysisError("ANALYSIS_FAILED", "safe", "")),
                        metadata,
                        REVISION.value()));

        mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.data").hasJsonPath())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.analyzedRevision").value(REVISION.value()));
    }

    @Test
    void should_return_business_read_forbidden_with_null_data() throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willReturn(RevisionBoundAnalysisResult.businessReadForbidden(
                        List.of(new AnalysisWarning("BUSINESS_READ_FORBIDDEN", "safe", "")),
                        metadata,
                        REVISION.value()));

        String body = mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUSINESS_READ_FORBIDDEN"))
                .andExpect(jsonPath("$.data").hasJsonPath())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.warnings[0].code").value("BUSINESS_READ_FORBIDDEN"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("target");
    }

    @Test
    void should_forward_fixture_pin_to_single_flattened_analysis() throws Exception {
        FlattenedCallGraph flattened = new FlattenedCallGraph(List.of(), "place(Order)", Map.of());
        given(semanticAnalysisApplicationService.analyzeFlattened(
                REPOSITORY_ID,
                Optional.of(RepositoryRevision.fixture()),
                "com.acme",
                "OrderService",
                "place(Order)"))
                .willReturn(RevisionBoundAnalysisResult.success(flattened, metadata, "FIXTURE"));

        mockMvc.perform(post("/v1/analyses/call-graph/flatten")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "packageName":"com.acme",
                                  "className":"OrderService",
                                  "methodSignature":"place(Order)",
                                  "expectedRevision":"FIXTURE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analyzedRevision").value("FIXTURE"));

        then(semanticAnalysisApplicationService).should().analyzeFlattened(
                REPOSITORY_ID,
                Optional.of(RepositoryRevision.fixture()),
                "com.acme",
                "OrderService",
                "place(Order)");
        then(semanticAnalysisApplicationService).shouldHaveNoMoreInteractions();
    }

    @Test
    void should_reject_invalid_expected_revision() throws Exception {
        mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST.replace("}", ",\"expectedRevision\":\"ABC\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"repoId", "packageName", "className", "methodSignature"})
    void should_reject_each_missing_identity_field(String field) throws Exception {
        String body = requestWithBlankField(field);
        mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_ignore_no_client_traversal_controls() throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willReturn(RevisionBoundAnalysisResult.success(graph, metadata, REVISION.value()));

        String body = mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST.replace("}", ",\"outputMode\":\"FLAT\",\"maxDepth\":99}")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("outputMode").doesNotContain("maxDepth");
        then(semanticAnalysisApplicationService).should().analyze(
                REPOSITORY_ID, Optional.empty(), "com.acme", "OrderService", "place(Order)");
    }

    @Test
    void should_return_ambiguous_candidates() throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willThrow(new SemanticAmbiguousMethodException(
                        "com.acme", "OrderService", "place", List.of("place(Order)", "place(Long)")));

        ResultActions result = performValidAnalysis()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_AMBIGUOUS_METHOD"))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0]").value("place(Order)"))
                .andExpect(jsonPath("$.candidates[1]").value("place(Long)"));
        assertStableError(result);
    }

    @Test
    void should_return_symbol_not_found() throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willThrow(new SemanticSymbolNotFoundException("SECRET"));

        assertStableError(performValidAnalysis()
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SEMANTIC_SYMBOL_NOT_FOUND")));
    }

    @Test
    void should_return_revision_mismatch_fields() throws Exception {
        RepositoryRevision expected = RepositoryRevision.ofSha("0".repeat(40));
        RepositoryRevision current = RepositoryRevision.ofSha("1".repeat(40));
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willThrow(new RepositoryRevisionMismatchException(expected, current));

        assertStableError(performValidAnalysis()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REPOSITORY_REVISION_MISMATCH"))
                .andExpect(jsonPath("$.currentRevision").value(current.value()))
                .andExpect(jsonPath("$.expectedRevision").value(expected.value())));
    }

    @ParameterizedTest
    @MethodSource("engineErrors")
    void should_return_stable_engine_error_without_default_spring_fields(
            RuntimeException failure, int statusCode, String errorCode) throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willThrow(failure);

        String body = performValidAnalysis()
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.errorCode").value(errorCode))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET");
    }

    static Stream<Arguments> engineErrors() {
        return Stream.of(
                Arguments.of(new SemanticEngineNotReadyException(), 503, "SEMANTIC_ENGINE_NOT_READY"),
                Arguments.of(new SemanticEngineStartFailedException(), 503, "SEMANTIC_ENGINE_START_FAILED"),
                Arguments.of(new SemanticRequestTimeoutException(), 504, "SEMANTIC_REQUEST_TIMEOUT"),
                Arguments.of(new SemanticProtocolException(), 500, "SEMANTIC_PROTOCOL_ERROR"));
    }

    @Test
    void should_return_safe_internal_error_for_unexpected_runtime() throws Exception {
        given(semanticAnalysisApplicationService.analyze(any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("SECRET"));

        String body = performValidAnalysis()
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("request failed"))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET");
    }

    @Test
    void should_return_safe_contract_body_for_checked_framework_failure() throws Exception {
        mockMvc.perform(get("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("request failed"))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());

        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_missing_analysis_token() throws Exception {
        mockMvc.perform(post("/v1/analyses/call-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());
        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_invalid_analysis_token() throws Exception {
        mockMvc.perform(post("/v1/analyses/call-graph")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized());
        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    private String requestWithBlankField(String field) {
        String repoId = "repoId".equals(field) ? "   " : "orders";
        String packageName = "packageName".equals(field) ? "   " : "com.acme";
        String className = "className".equals(field) ? "   " : "OrderService";
        String methodSignature = "methodSignature".equals(field) ? "   " : "place(Order)";
        return """
                {
                  "repoId":"%s",
                  "packageName":"%s",
                  "className":"%s",
                  "methodSignature":"%s"
                }
                """.formatted(repoId, packageName, className, methodSignature);
    }

    private ResultActions performValidAnalysis() throws Exception {
        return mockMvc.perform(post("/v1/analyses/call-graph")
                .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST));
    }

    private void assertStableError(ResultActions result) throws Exception {
        result.andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }
}
