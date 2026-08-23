package com.java.semantic.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.security.ApiSecurityProperties;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.api.dto.ConceptKindUnavailableResponse;
import com.java.semantic.api.dto.DiscoverConceptsRequest;
import com.java.semantic.syntax.application.concept.ConceptKind;
import com.java.semantic.syntax.application.concept.ConceptMatchMode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiMonitoringFilterTest {

    @Test
    void should_emit_one_completion_event_for_a_successful_request() throws Exception {
        CapturedLogs capturedLogs = captureLogs();
        try {
            FilterResult result = invoke((request, response) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/repositories/{repoId}");
                ApiMonitoringContext.find((HttpServletRequest) request).orElseThrow()
                        .recordResponseBody(new SuccessPayload("repo-42"));
                ((HttpServletResponse) response).setStatus(200);
            });

            assertCompletion(capturedLogs,
                    "http_api_completed requestId=request-42 method=GET requestPath=/v1/repositories/repo-42 route=/v1/repositories/{repoId} observedStatus=200 outcome=COMPLETED errorCode= monitoringFieldsTruncated=false");
            assertThat(result.response().getStatus()).isEqualTo(200);
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_emit_one_completion_event_for_a_handled_error() throws Exception {
        CapturedLogs capturedLogs = captureLogs();
        try {
            invoke((request, response) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/repositories/{repoId}");
                ApiMonitoringContext.find((HttpServletRequest) request).orElseThrow()
                        .recordErrorCode("REPOSITORY_NOT_FOUND");
                ((HttpServletResponse) response).setStatus(404);
            });

            assertCompletion(capturedLogs,
                    "http_api_completed requestId=request-42 method=GET requestPath=/v1/repositories/repo-42 route=/v1/repositories/{repoId} observedStatus=404 outcome=COMPLETED errorCode=REPOSITORY_NOT_FOUND monitoringFieldsTruncated=false");
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_extract_the_typed_unavailable_kind_error_code_for_the_completion_event() throws Exception {
        CapturedLogs capturedLogs = captureLogs();
        try {
            invoke((request, response) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/discovery/concepts");
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((HttpServletRequest) request));
                try {
                    new ApiMonitoringResponseBodyAdvice().beforeBodyWrite(
                            new ConceptKindUnavailableResponse(
                                    "CONCEPT_KIND_UNAVAILABLE",
                                    "requested concept kind is not active",
                                    List.of("SQL_IDENTIFIER"),
                                    List.of("METHOD"),
                                    "request-42"),
                            null,
                            MediaType.APPLICATION_JSON,
                            null,
                            null,
                            new ServletServerHttpResponse((HttpServletResponse) response));
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
                ((HttpServletResponse) response).setStatus(422);
            });

            assertCompletion(capturedLogs,
                    "http_api_completed requestId=request-42 method=GET requestPath=/v1/repositories/repo-42 route=/v1/discovery/concepts observedStatus=422 outcome=COMPLETED errorCode=CONCEPT_KIND_UNAVAILABLE monitoringFieldsTruncated=false");
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_monitor_discover_concepts_request_metadata_without_term_values_or_absolute_paths() throws Exception {
        CapturedLogs capturedLogs = captureLogs();
        try {
            DiscoverConceptsRequest requestBody = new DiscoverConceptsRequest();
            requestBody.setRepoId("orders");
            requestBody.setExpectedRevision("1111111111111111111111111111111111111111");
            requestBody.setTerms(List.of(new DiscoverConceptsRequest.Term(
                    "customer-secret-search-text", ConceptMatchMode.TOKEN_EXACT)));
            requestBody.setKinds(List.of(ConceptKind.METHOD));
            requestBody.setOperator("ALL");
            requestBody.setPackagePrefix("/private/repository/path");

            invoke((request, response) -> {
                ApiMonitoringContext.find((HttpServletRequest) request).orElseThrow()
                        .recordRequestBody(requestBody);
                ((HttpServletResponse) response).setStatus(200);
            });

            List<String> messages = capturedLogs.events().stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(messages)
                    .anyMatch(message -> message.contains("segment=request.repoId=orders"))
                    .anyMatch(message -> message.contains("segment=request.terms.size=1"))
                    .anyMatch(message -> message.contains("segment=request.kinds.size=1"))
                    .noneMatch(message -> message.contains("customer-secret-search-text"))
                    .noneMatch(message -> message.contains("/private/repository/path"));
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_emit_one_completion_event_for_an_authentication_failure() throws Exception {
        CapturedLogs capturedLogs = captureLogs();
        try {
            ApiSecurityProperties properties = new ApiSecurityProperties();
            properties.setApiToken("configured");
            ApiTokenFilter tokenFilter = new ApiTokenFilter(properties, new ObjectMapper());

            FilterResult result = invoke((request, response) -> tokenFilter.doFilter(
                    request, response, (downstreamRequest, downstreamResponse) -> {
                        throw new AssertionError("authentication failure must not invoke downstream");
                    }));

            assertCompletion(capturedLogs,
                    "http_api_completed requestId=request-42 method=GET requestPath=/v1/repositories/repo-42 route=UNMAPPED observedStatus=401 outcome=COMPLETED errorCode=SEMANTIC_UNAUTHORIZED monitoringFieldsTruncated=false");
            assertThat(result.response().getStatus()).isEqualTo(401);
        } finally {
            capturedLogs.stop();
        }
    }

    @Test
    void should_emit_one_completion_event_and_rethrow_an_unexpected_exception_unchanged() {
        CapturedLogs capturedLogs = captureLogs();
        ServletException failure = new ServletException("unexpected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            assertThatThrownBy(() -> invoke((request, chainedResponse) -> {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/analyses/call-graphs/outgoing");
                ApiMonitoringContext.find((HttpServletRequest) request).orElseThrow()
                        .recordResponseBody(new NullableSuccessPayload(null));
                ((HttpServletResponse) chainedResponse).setStatus(503);
                throw failure;
            }, response))
                    .isSameAs(failure);

            assertCompletion(capturedLogs,
                    "http_api_completed requestId=request-42 method=GET requestPath=/v1/repositories/repo-42 route=/v1/analyses/call-graphs/outgoing observedStatus=503 outcome=UNHANDLED_EXCEPTION errorCode= monitoringFieldsTruncated=false");
            assertThat(response.getStatus()).isEqualTo(503);
        } finally {
            capturedLogs.stop();
        }
    }

    private FilterResult invoke(FilterChain downstream) throws Exception {
        return invoke(downstream, new MockHttpServletResponse());
    }

    private FilterResult invoke(FilterChain downstream, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/repositories/repo-42");
        request.setQueryString("include=details");
        request.setAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE, "request-42");

        new ApiMonitoringFilter().doFilter(request, response, downstream);

        return new FilterResult(response);
    }

    private CapturedLogs captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiMonitoringFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private List<String> completionMessages(List<ILoggingEvent> events) {
        return events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("http_api_completed"))
                .toList();
    }

    private void assertCompletion(CapturedLogs capturedLogs, String expectedPrefix) {
        assertThat(completionMessages(capturedLogs.events()))
                .singleElement()
                .matches(message -> message.startsWith(expectedPrefix)
                        && message.substring(expectedPrefix.length()).matches(" durationMillis=\\d+"));
    }

    private record SuccessPayload(@MonitoringField(MonitoringMode.VALUE) String repositoryId) {
    }

    private record NullableSuccessPayload(
            @MonitoringField(MonitoringMode.NESTED) SuccessPayload nested) {
    }

    private record FilterResult(MockHttpServletResponse response) {
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) {

        List<ILoggingEvent> events() {
            return List.copyOf(appender.list);
        }

        void stop() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
