package com.java.semantic.api.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenFilterTest {

    private static final String CONFIGURED_TOKEN = "s3cret-token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void should_reject_every_request_when_token_is_not_configured() throws Exception {
        FilterResult result = invoke(filterWith(""), "GET", "/v1/repositories", CONFIGURED_TOKEN);

        assertThat(result.response().getStatus()).isEqualTo(403);
        assertThat(result.filterChain().getRequest()).isNull();
        assertJsonError(result.response(), "SEMANTIC_AUTH_DISABLED",
                "semantic.api.api-token is not configured; the service refuses all traffic");
    }

    @Test
    void should_reject_when_token_header_is_missing() throws Exception {
        FilterResult result = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
        assertJsonError(result.response(), "SEMANTIC_UNAUTHORIZED", "X-Api-Token header is required");
        assertThat(OBJECT_MAPPER.readTree(result.response().getContentAsString()).path("requestId").asText())
                .isEqualTo("request-42");
        assertThat(OBJECT_MAPPER.readTree(result.response().getContentAsString()).path("candidates").isArray())
                .isTrue();
    }

    @Test
    void should_reject_when_token_is_wrong() throws Exception {
        FilterResult result = invoke(filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories", "wrong");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    @Test
    void should_pass_through_when_token_matches() throws Exception {
        FilterResult result =
                invoke(filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories", CONFIGURED_TOKEN);

        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.filterChain().getRequest()).isNotNull();
    }

    @Test
    void should_reject_read_requests_too_when_token_is_missing() throws Exception {
        // 與 agent 的 filter 不同:此服務的讀取端點會回傳私有原始碼的分析結果,GET 不豁免
        FilterResult result = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories/x");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    @Test
    void should_allow_health_without_a_token_when_compose_probes_it() throws Exception {
        FilterResult result = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/actuator/health");

        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(result.filterChain().getRequest()).isNotNull();
    }

    @Test
    void should_reject_when_path_only_starts_with_the_health_path() throws Exception {
        FilterResult result = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/actuator/health-extra");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/analyses/call-graph",
            "/v1/api-routes/lookup"
    })
    void should_reject_new_semantic_endpoints_when_token_is_missing(String path) throws Exception {
        FilterResult result = invokeWithoutToken(filterWith(CONFIGURED_TOKEN), "POST", path);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    @Test
    void should_reject_entry_point_endpoint_when_token_is_missing() throws Exception {
        FilterResult result = invokeWithoutToken(
                filterWith(CONFIGURED_TOKEN), "GET", "/v1/repositories/orders/entry-points");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    @Test
    void should_reject_new_analysis_endpoint_when_token_is_wrong() throws Exception {
        FilterResult result = invoke(
                filterWith(CONFIGURED_TOKEN), "POST", "/v1/analyses/call-graph", "wrong");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    @Test
    void should_reject_new_route_endpoint_when_token_is_wrong() throws Exception {
        FilterResult result = invoke(
                filterWith(CONFIGURED_TOKEN), "POST", "/v1/api-routes/lookup", "wrong");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.filterChain().getRequest()).isNull();
    }

    private ApiTokenFilter filterWith(String token) {
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiToken(token);
        return new ApiTokenFilter(properties, OBJECT_MAPPER);
    }

    private FilterResult invoke(ApiTokenFilter filter, String method, String uri, String token)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(ApiTokenFilter.API_TOKEN_HEADER, token);
        return invoke(filter, request);
    }

    private FilterResult invokeWithoutToken(ApiTokenFilter filter, String method, String uri)
            throws Exception {
        return invoke(filter, new MockHttpServletRequest(method, uri));
    }

    private FilterResult invoke(ApiTokenFilter filter, MockHttpServletRequest request)
            throws Exception {
        request.setAttribute("semantic.requestId", "request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        filter.doFilter(request, response, filterChain);
        return new FilterResult(response, filterChain);
    }

    private void assertJsonError(MockHttpServletResponse response, String errorCode, String message)
            throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsString());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body.path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(body.path("message").asText()).isEqualTo(message);
    }

    private record FilterResult(MockHttpServletResponse response, MockFilterChain filterChain) {
    }
}
