package com.java.semantic.api;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    @Test
    void should_preserve_a_valid_request_id_and_clear_mdc_after_the_request() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "request-42.abc");
        AtomicReference<String> requestMdc = new AtomicReference<>();
        FilterChain chain = (chainRequest, chainResponse) -> requestMdc.set(MDC.get("requestId"));

        new RequestCorrelationFilter().doFilter(request, response, chain);

        assertThat(request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("request-42.abc");
        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("request-42.abc");
        assertThat(requestMdc.get()).isEqualTo("request-42.abc");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void should_replace_an_invalid_client_request_id_with_a_uuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/repositories");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "unsafe request id");

        new RequestCorrelationFilter().doFilter(request, response, (chainRequest, chainResponse) -> { });

        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER))
                .matches("[A-Za-z0-9._-]{1,64}")
                .isNotEqualTo("unsafe request id");
    }
}
