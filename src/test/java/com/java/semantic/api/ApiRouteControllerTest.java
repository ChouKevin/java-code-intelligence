package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.trie.ApiEntryPointRef;
import com.java.semantic.trie.ApiRouteApplicationService;
import com.java.semantic.trie.ApiRouteCandidate;
import com.java.semantic.trie.ApiRouteMatch;
import com.java.semantic.trie.ApiRouteMatchBatch;
import com.java.semantic.trie.ApiRouteObservation;
import com.java.semantic.trie.ApiRouteObservationCode;
import com.java.semantic.syntax.domain.AnalysisTargetStatus;
import com.java.semantic.syntax.domain.MethodTargetResolution;
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class ApiRouteControllerTest {

    private static final String TOKEN = "test-token";
    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision SHA_TWO = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiRouteApplicationService apiRouteApplicationService;

    @Test
    void should_return_multiple_candidates_with_per_candidate_revisions() throws Exception {
        given(apiRouteApplicationService.lookupMatches(
                "/orders/42", Optional.of("GET"), Optional.empty()))
                .willReturn(batch(
                        candidate("repo-a", SHA_ONE.value()),
                        candidate("repo-b", SHA_TWO.value())));

        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"apiPath":"/orders/42","httpMethod":"GET","repoScope":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].repoId").value("repo-a"))
                .andExpect(jsonPath("$.candidates[0].analyzedRevision").value(SHA_ONE.value()))
                .andExpect(jsonPath("$.analyzedRevision").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].apiPath").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].matchKind").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].matchReasons").isEmpty())
                .andExpect(jsonPath("$.observations").isEmpty());
    }

    @Test
    void should_return_ok_with_empty_candidate_list() throws Exception {
        given(apiRouteApplicationService.lookupMatches("/missing", Optional.empty(), Optional.empty()))
                .willReturn(batch());

        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/missing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21})
    void should_reject_suggest_limit_outside_contract(int limit) throws Exception {
        mockMvc.perform(post("/v1/api-routes/suggest")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\",\"limit\":" + limit + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        then(apiRouteApplicationService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void should_accept_suggest_limit_boundary(int limit) throws Exception {
        given(apiRouteApplicationService.suggestMatches(
                "/orders", Optional.empty(), Optional.empty(), limit)).willReturn(batch());

        mockMvc.perform(post("/v1/api-routes/suggest")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\",\"limit\":" + limit + "}"))
                .andExpect(status().isOk());
        then(apiRouteApplicationService).should().suggestMatches(
                "/orders", Optional.empty(), Optional.empty(), limit);
    }

    @Test
    void should_reject_invalid_repository_scope() throws Exception {
        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\",\"repoScope\":\"../secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        then(apiRouteApplicationService).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("malformedRouteInputs")
    void should_map_invalid_path_or_method_to_request_invalid(String json) throws Exception {
        given(apiRouteApplicationService.lookupMatches(any(), any(), any()))
                .willThrow(new IllegalArgumentException("SECRET"));

        String body = mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET");
    }

    static Stream<String> malformedRouteInputs() {
        return Stream.of(
                "{\"apiPath\":\"malformed\"}",
                "{\"apiPath\":\"/orders\",\"httpMethod\":\"BAD METHOD\"}");
    }

    @Test
    void should_forward_lookup_scope_and_method() throws Exception {
        RepositoryId scope = RepositoryId.of("orders");
        given(apiRouteApplicationService.lookupMatches("/orders/42", Optional.of("POST"), Optional.of(scope)))
                .willReturn(batch());

        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders/42\",\"httpMethod\":\"POST\",\"repoScope\":\"orders\"}"))
                .andExpect(status().isOk());
        then(apiRouteApplicationService).should().lookupMatches(
                "/orders/42", Optional.of("POST"), Optional.of(scope));
    }

    @Test
    void should_preserve_suggestion_candidate_order() throws Exception {
        ApiRouteCandidate first = candidate("repo-a", SHA_ONE.value());
        ApiRouteCandidate second = candidate("repo-b", SHA_TWO.value());
        given(apiRouteApplicationService.suggestMatches(
                "/orders", Optional.empty(), Optional.empty(), 2))
                .willReturn(batch(first, second));

        mockMvc.perform(post("/v1/api-routes/suggest")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\",\"limit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].repoId").value("repo-a"))
                .andExpect(jsonPath("$.candidates[1].repoId").value("repo-b"));
    }

    @Test
    void should_serialize_truncation_observation() throws Exception {
        given(apiRouteApplicationService.suggestMatches(
                "/orders", Optional.empty(), Optional.empty(), 1))
                .willReturn(new ApiRouteMatchBatch(
                        List.of(),
                        List.of(new ApiRouteObservation(
                                ApiRouteObservationCode.TRUNCATED_CANDIDATES,
                                "route candidates were truncated by the requested limit"))));

        mockMvc.perform(post("/v1/api-routes/suggest")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isEmpty())
                .andExpect(jsonPath("$.observations[0].code").value("TRUNCATED_CANDIDATES"))
                .andExpect(jsonPath("$.observations[0].description")
                        .value("route candidates were truncated by the requested limit"));
    }

    @Test
    void should_not_serialize_route_identity_or_exception_sentinels() throws Exception {
        given(apiRouteApplicationService.lookupMatches(any(), any(), any()))
                .willReturn(batch(candidate("repo-a", SHA_ONE.value())));

        String body = mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].repoId").exists())
                .andExpect(jsonPath("$.candidates[0].analyzedRevision").exists())
                .andExpect(jsonPath("$.candidates[0].httpMethod").exists())
                .andExpect(jsonPath("$.candidates[0].routeTemplate").exists())
                .andExpect(jsonPath("$.candidates[0].sourceType.javaType.packageName").exists())
                .andExpect(jsonPath("$.candidates[0].sourceType.javaType.className").exists())
                .andExpect(jsonPath("$.candidates[0].sourceType.sourceFile").exists())
                .andExpect(jsonPath("$.candidates[0].packageName").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].className").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].methodName").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SECRET").doesNotContain("exception");
    }

    @Test
    void should_serialize_ambiguous_route_target_candidates_in_complete_value_order() throws Exception {
        given(apiRouteApplicationService.lookupMatches(any(), any(), any()))
                .willReturn(batch(candidate("repo-a", SHA_ONE.value())));

        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].analysisTarget.status").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.target").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].analysisTarget.candidates[0].sourceType.sourceFile")
                        .value("src/main/java/com/acme/AController.java"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.candidates[1].sourceType.sourceFile")
                        .value("src/main/java/com/acme/ZController.java"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.reasonCode").value("OVERLOAD_AMBIGUOUS"));
    }

    @Test
    void should_serialize_an_unresolved_route_target_without_reconstructing_display_fields() throws Exception {
        given(apiRouteApplicationService.lookupMatches(any(), any(), any()))
                .willReturn(batch(new ApiRouteCandidate(
                        "repo-a",
                        SHA_ONE.value(),
                        "GET",
                        "/orders/{id}",
                        new SourceTypeIdentity(
                                new JavaTypeIdentity("com.acme.display", "DisplayController"),
                                "src/main/java/com/acme/display/DisplayController.java"),
                        "displayMethod",
                        MethodTargetResolution.unresolved("METHOD_PARAMETER_BINDING_UNRESOLVED"),
                        List.of())));

        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].analysisTarget.status").value("UNRESOLVED"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.target").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].analysisTarget.candidates").isEmpty())
                .andExpect(jsonPath("$.candidates[0].sourceType.javaType.packageName").value("com.acme.display"))
                .andExpect(jsonPath("$.candidates[0].sourceType.javaType.className").value("DisplayController"))
                .andExpect(jsonPath("$.candidates[0].methodName").value("displayMethod"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.reasonCode")
                        .value("METHOD_PARAMETER_BINDING_UNRESOLVED"));
    }

    @Test
    void should_serialize_resolved_outer_identity_equal_to_the_nested_target() throws Exception {
        SourceTypeIdentity sourceType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.acme.order", "OrderController"),
                "module-a/src/main/java/com/acme/order/OrderController.java");
        MethodTarget target = new MethodTarget(sourceType, "getOrder", List.of("java.lang.String"));
        given(apiRouteApplicationService.lookupMatches(any(), any(), any()))
                .willReturn(batch(new ApiRouteCandidate(
                        "repo-a",
                        SHA_ONE.value(),
                        "GET",
                        "/orders/{id}",
                        sourceType,
                        "getOrder",
                        MethodTargetResolution.resolved(target),
                        List.of())));

        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders/42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].sourceType.javaType.packageName")
                        .value("com.acme.order"))
                .andExpect(jsonPath("$.candidates[0].sourceType.javaType.className")
                        .value("OrderController"))
                .andExpect(jsonPath("$.candidates[0].sourceType.sourceFile")
                        .value("module-a/src/main/java/com/acme/order/OrderController.java"))
                .andExpect(jsonPath("$.candidates[0].methodName").value("getOrder"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.target.sourceType.javaType.packageName")
                        .value("com.acme.order"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.target.sourceType.javaType.className")
                        .value("OrderController"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.target.sourceType.sourceFile")
                        .value("module-a/src/main/java/com/acme/order/OrderController.java"))
                .andExpect(jsonPath("$.candidates[0].analysisTarget.target.methodName").value("getOrder"));
    }

    @Test
    void should_reject_missing_route_token() throws Exception {
        mockMvc.perform(post("/v1/api-routes/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\"}"))
                .andExpect(status().isUnauthorized());
        then(apiRouteApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_invalid_route_token() throws Exception {
        mockMvc.perform(post("/v1/api-routes/lookup")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiPath\":\"/orders\"}"))
                .andExpect(status().isUnauthorized());
        then(apiRouteApplicationService).shouldHaveNoInteractions();
    }

    private static ApiRouteCandidate candidate(String repoId, String revision) {
        return new ApiRouteCandidate(
                repoId,
                revision,
                "GET",
                "/orders/{*}",
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", "OrderController"),
                        "src/main/java/com/acme/order/OrderController.java"),
                "getOrder",
                new MethodTargetResolution(
                        AnalysisTargetStatus.AMBIGUOUS,
                        Optional.empty(),
                        List.of(
                                new MethodTarget(
                                        new SourceTypeIdentity(
                                                new JavaTypeIdentity("com.acme", "ZController"),
                                                "src/main/java/com/acme/ZController.java"),
                                        "getOrder",
                                        List.of("java.lang.String")),
                                new MethodTarget(
                                        new SourceTypeIdentity(
                                                new JavaTypeIdentity("com.acme", "AController"),
                                                "src/main/java/com/acme/AController.java"),
                                        "getOrder",
                                        List.of("java.lang.Long"))),
                        "OVERLOAD_AMBIGUOUS"),
                List.of());
    }

    private static ApiRouteMatchBatch batch(ApiRouteCandidate... candidates) {
        List<ApiRouteMatch> matches = Arrays.stream(candidates)
                .map(candidate -> new ApiRouteMatch(
                        new ApiEntryPointRef(
                                candidate.repoId(),
                                candidate.analyzedRevision(),
                                candidate.sourceType(),
                                candidate.methodName(),
                                candidate.httpMethod(),
                                candidate.routeTemplate(),
                                candidate.analysisTarget()),
                        candidate.matchReasons()))
                .toList();
        return new ApiRouteMatchBatch(matches, List.of());
    }
}
