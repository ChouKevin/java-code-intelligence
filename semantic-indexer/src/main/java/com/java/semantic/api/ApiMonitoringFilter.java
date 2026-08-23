package com.java.semantic.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 在 API 呼叫完成後輸出一次有界且白名單化的監控事件 */
public final class ApiMonitoringFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiMonitoringFilter.class);
    private static final String UNMAPPED_ROUTE = "UNMAPPED";

    private final ApiMonitoringRenderer renderer = new ApiMonitoringRenderer();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        ApiMonitoringContext context = ApiMonitoringContext.initialize(request);
        long startedAt = System.nanoTime();
        CompletionOutcome outcome = CompletionOutcome.COMPLETED;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            outcome = CompletionOutcome.UNHANDLED_EXCEPTION;
            throw exception;
        } catch (Error error) {
            outcome = CompletionOutcome.UNHANDLED_EXCEPTION;
            throw error;
        } finally {
            emitCompletion(request, response, context, outcome, elapsedMillis(startedAt));
        }
    }

    private void emitCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            ApiMonitoringContext context,
            CompletionOutcome outcome,
            long durationMillis) {
        ApiMonitoringRenderer.RenderedApiMonitoring rendered = renderWithoutChangingResponse(context);
        String requestId = requestId(request);
        for (String segment : rendered.segments()) {
            log.info("http_api_monitoring_segment requestId={} segment={}", requestId, segment);
        }
        log.info(
                "http_api_completed requestId={} method={} requestPath={} route={} observedStatus={} outcome={} "
                        + "errorCode={} monitoringFieldsTruncated={} durationMillis={}",
                requestId,
                request.getMethod(),
                request.getRequestURI(),
                route(request),
                response.getStatus(),
                outcome,
                context.errorCode().orElse(""),
                rendered.monitoringFieldsTruncated(),
                durationMillis);
    }

    private ApiMonitoringRenderer.RenderedApiMonitoring renderWithoutChangingResponse(ApiMonitoringContext context) {
        try {
            return renderer.render(context);
        } catch (RuntimeException exception) {
            log.error("http_api_monitoring_contract_defect exceptionType={}", exception.getClass().getSimpleName());
            return new ApiMonitoringRenderer.RenderedApiMonitoring(List.of(), false);
        }
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String identifier && StringUtils.hasText(identifier)) {
            return identifier;
        }
        return "";
    }

    private String route(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (attribute instanceof String route && StringUtils.hasText(route)) {
            return route;
        }
        return UNMAPPED_ROUTE;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private enum CompletionOutcome {
        COMPLETED,
        UNHANDLED_EXCEPTION
    }
}
