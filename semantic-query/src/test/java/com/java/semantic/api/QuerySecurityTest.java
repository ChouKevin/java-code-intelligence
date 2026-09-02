package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySecurityTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QuerySecurityConfiguration.class);

    @Test
    void security_configuration_starts_and_registers_both_query_filters() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("queryRequestMonitoringFilter", FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(QueryRequestMonitoringFilter.class);
            assertThat(context.getBean("queryTokenFilter", FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(QueryTokenFilter.class);
        });
    }

    @Test
    void security_and_monitoring_filters_register_for_application_relative_query_routes() {
        QuerySecurityConfiguration configuration = new QuerySecurityConfiguration();
        QuerySecurityProperties properties = new QuerySecurityProperties();

        FilterRegistrationBean<QueryRequestMonitoringFilter> monitoringRegistration = configuration.queryRequestMonitoringFilter();
        FilterRegistrationBean<QueryTokenFilter> tokenRegistration = configuration.queryTokenFilter(properties);

        assertThat(monitoringRegistration.getUrlPatterns()).containsExactlyInAnyOrder("/api/v1/*", "/mcp");
        assertThat(tokenRegistration.getUrlPatterns()).containsExactlyInAnyOrder("/api/v1/*", "/mcp");
    }

    @Test
    void query_http_and_mcp_paths_require_the_query_token_but_health_is_not_a_read_surface() throws Exception {
        QuerySecurityProperties properties = new QuerySecurityProperties();
        properties.setApiToken("query-token");
        QueryTokenFilter filter = new QueryTokenFilter(properties);

        MockHttpServletRequest absentHttpToken = new MockHttpServletRequest("POST", "/api/v1/search-code");
        MockHttpServletResponse absentHttpResponse = new MockHttpServletResponse();
        filter.doFilter(absentHttpToken, absentHttpResponse, (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(absentHttpResponse.getStatus()).isEqualTo(401);
        assertThat(absentHttpResponse.getContentType()).startsWith("application/json");
        assertThat(absentHttpResponse.getContentAsString()).isEqualTo("{\"code\":\"QUERY_TOKEN_REQUIRED\",\"retryable\":false}");

        MockHttpServletRequest mcpToken = new MockHttpServletRequest("POST", "/mcp");
        mcpToken.addHeader(QueryTokenFilter.TOKEN_HEADER, "query-token");
        MockHttpServletResponse mcpResponse = new MockHttpServletResponse();
        filter.doFilter(mcpToken, mcpResponse, (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(mcpResponse.getStatus()).isEqualTo(204);

        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(health)).isTrue();
    }

    @Test
    void query_http_and_mcp_paths_remain_protected_below_a_servlet_context_path() throws Exception {
        QuerySecurityProperties properties = new QuerySecurityProperties();
        properties.setApiToken("query-token");
        QueryTokenFilter filter = new QueryTokenFilter(properties);

        for (String servletPath : new String[]{"/api/v1/search-code", "/mcp"}) {
            MockHttpServletRequest absentToken = requestUnderContext(servletPath);
            MockHttpServletResponse absentTokenResponse = new MockHttpServletResponse();
            filter.doFilter(absentToken, absentTokenResponse, (request, response) ->
                    ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
            assertThat(absentTokenResponse.getStatus()).isEqualTo(401);

            MockHttpServletRequest validToken = requestUnderContext(servletPath);
            validToken.addHeader(QueryTokenFilter.TOKEN_HEADER, "query-token");
            MockHttpServletResponse validTokenResponse = new MockHttpServletResponse();
            filter.doFilter(validToken, validTokenResponse, (request, response) ->
                    ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
            assertThat(validTokenResponse.getStatus()).isEqualTo(204);
        }
    }

    @Test
    void disabled_query_access_returns_the_safe_forbidden_envelope_without_parser_or_token_details() throws Exception {
        QuerySecurityProperties properties = new QuerySecurityProperties();
        QueryTokenFilter filter = new QueryTokenFilter(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/repositories"), response, (request, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(204));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"code\":\"QUERY_ACCESS_DISABLED\",\"retryable\":false}");
    }

    private static MockHttpServletRequest requestUnderContext(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/semantic" + servletPath);
        request.setContextPath("/semantic");
        request.setServletPath(servletPath);
        return request;
    }
}
