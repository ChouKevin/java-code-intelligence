package com.java.semantic.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRequestMonitoringFilterTest {

    @Test
    void returns_a_bounded_request_correlation_id_without_echoing_an_untrusted_value() throws Exception {
        QueryRequestMonitoringFilter filter = new QueryRequestMonitoringFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/code-facts/search");
        request.addHeader(QueryRequestMonitoringFilter.REQUEST_ID_HEADER, "x".repeat(1025));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(204));

        assertThat(response.getHeader(QueryRequestMonitoringFilter.REQUEST_ID_HEADER)).hasSizeBetween(1, 128)
                .isNotEqualTo("x".repeat(1025));
    }

    @Test
    void monitors_context_path_mcp_requests_and_preserves_a_safe_caller_correlation_id() throws Exception {
        QueryRequestMonitoringFilter filter = new QueryRequestMonitoringFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/semantic/mcp");
        request.setContextPath("/semantic");
        request.addHeader(QueryRequestMonitoringFilter.REQUEST_ID_HEADER, "request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(204));

        assertThat(response.getHeader(QueryRequestMonitoringFilter.REQUEST_ID_HEADER)).isEqualTo("request-42");
    }
}
