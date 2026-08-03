package com.java.semantic.mcp.monitoring;

import com.java.semantic.api.RequestCorrelationFilter;
import com.java.semantic.api.security.ApiTokenFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 在 MCP transport 邊界記錄結果而不讀取 JSON-RPC payload */
public final class McpTransportMonitoringFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpTransportMonitoringFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/mcp".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
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
            log.info(
                    "mcp_transport_completed requestId={} method={} requestPath={} observedStatus={} outcome={} errorCode={} durationMillis={}",
                    requestId(request),
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    outcome,
                    Objects.toString(request.getAttribute(ApiTokenFilter.AUTH_ERROR_CODE_ATTRIBUTE), ""),
                    elapsedMillis(startedAt));
        }
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String requestId && StringUtils.hasText(requestId)) {
            return requestId;
        }
        return "";
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private enum CompletionOutcome {
        COMPLETED,
        UNHANDLED_EXCEPTION
    }
}
