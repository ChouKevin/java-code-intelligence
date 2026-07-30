package com.java.semantic.api.security;

import com.java.semantic.api.RequestCorrelationFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSecurityConfigTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void should_register_filter_globally_when_configuring_api_security() {
        ApiSecurityConfig config = new ApiSecurityConfig();
        ApiSecurityProperties properties = new ApiSecurityProperties();

        FilterRegistrationBean<ApiTokenFilter> registration = config.apiTokenFilter(properties);

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(registration.getFilter()).isInstanceOf(ApiTokenFilter.class);
    }

    @Test
    void should_register_correlation_before_token_authentication() {
        ApiSecurityConfig config = new ApiSecurityConfig();

        FilterRegistrationBean<RequestCorrelationFilter> registration = config.requestCorrelationFilter();

        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(registration.getFilter()).isInstanceOf(RequestCorrelationFilter.class);
    }

    @Test
    void should_correlate_before_rejecting_an_unauthorized_request() throws Exception {
        ApiSecurityConfig config = new ApiSecurityConfig();
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiToken("configured");

        ChainResult result = invoke(
                config, properties, "POST", "/v1/discovery/event-listeners", "valid.request-42");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.response().getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
                .isEqualTo("valid.request-42");
        assertThat(result.body().path("requestId").asText()).isEqualTo("valid.request-42");
        assertThat(result.body().path("candidates").isArray()).isTrue();
        assertThat(result.body().path("candidates")).isEmpty();
        assertThat(result.downstreamInvoked()).isFalse();
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void should_reject_incorrect_token_for_event_listener_discovery() throws Exception {
        ApiSecurityConfig config = new ApiSecurityConfig();
        ApiSecurityProperties properties = new ApiSecurityProperties();
        properties.setApiToken("configured");

        ChainResult result = invoke(
                config, properties, "POST", "/v1/discovery/event-listeners", "valid.request-42", "incorrect");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.downstreamInvoked()).isFalse();
    }

    @Test
    void should_correlate_before_rejecting_when_authentication_is_disabled() throws Exception {
        ApiSecurityConfig config = new ApiSecurityConfig();
        ApiSecurityProperties properties = new ApiSecurityProperties();

        ChainResult result = invoke(
                config, properties, "POST", "/v1/discovery/event-listeners", "not valid request id");

        String requestId = result.response().getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(result.response().getStatus()).isEqualTo(403);
        assertThat(requestId).matches("[A-Za-z0-9._-]{1,64}").isNotEqualTo("not valid request id");
        assertThat(result.body().path("requestId").asText()).isEqualTo(requestId);
        assertThat(result.body().path("candidates").isArray()).isTrue();
        assertThat(result.body().path("candidates")).isEmpty();
        assertThat(result.downstreamInvoked()).isFalse();
        assertThat(MDC.get("requestId")).isNull();
    }

    private ChainResult invoke(
            ApiSecurityConfig config,
            ApiSecurityProperties properties,
            String method,
            String path,
            String suppliedRequestId)
            throws Exception {
        return invoke(config, properties, method, path, suppliedRequestId, null);
    }

    private ChainResult invoke(
            ApiSecurityConfig config,
            ApiSecurityProperties properties,
            String method,
            String path,
            String suppliedRequestId,
            String apiToken)
            throws Exception {
        RequestCorrelationFilter correlation = config.requestCorrelationFilter().getFilter();
        ApiTokenFilter token = config.apiTokenFilter(properties).getFilter();
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, suppliedRequestId);
        Optional.ofNullable(apiToken).ifPresent(value -> request.addHeader(ApiTokenFilter.API_TOKEN_HEADER, value));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamInvoked = new AtomicBoolean();
        FilterChain authenticationChain = (chainedRequest, chainedResponse) -> token.doFilter(
                chainedRequest,
                chainedResponse,
                (downstreamRequest, downstreamResponse) -> downstreamInvoked.set(true));

        correlation.doFilter(request, response, authenticationChain);

        return new ChainResult(response, OBJECT_MAPPER.readTree(response.getContentAsString()), downstreamInvoked.get());
    }

    private record ChainResult(MockHttpServletResponse response, JsonNode body, boolean downstreamInvoked) {
    }
}
