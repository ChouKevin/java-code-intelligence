package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySecurityTest {
    @Test
    void query_http_and_mcp_paths_require_the_query_token_but_health_is_not_a_read_surface() throws Exception {
        QuerySecurityProperties properties = new QuerySecurityProperties();
        properties.setApiToken("query-token");
        QueryTokenFilter filter = new QueryTokenFilter(properties);

        MockHttpServletRequest absentHttpToken = new MockHttpServletRequest("POST", "/v1/code-facts/search");
        MockHttpServletResponse absentHttpResponse = new MockHttpServletResponse();
        filter.doFilter(absentHttpToken, absentHttpResponse, (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(absentHttpResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest mcpToken = new MockHttpServletRequest("POST", "/mcp");
        mcpToken.addHeader(QueryTokenFilter.TOKEN_HEADER, "query-token");
        MockHttpServletResponse mcpResponse = new MockHttpServletResponse();
        filter.doFilter(mcpToken, mcpResponse, (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(204));
        assertThat(mcpResponse.getStatus()).isEqualTo(204);

        MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(health)).isTrue();
    }
}
