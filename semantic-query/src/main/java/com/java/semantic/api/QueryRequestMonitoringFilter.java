package com.java.semantic.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Emits bounded completion metadata for Query HTTP and MCP requests without recording request or result content. */
public final class QueryRequestMonitoringFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String REQUEST_ID_MDC_KEY = "requestId";
    private static final int MAX_REQUEST_ID_LENGTH = 128;
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Logger LOG = LoggerFactory.getLogger(QueryRequestMonitoringFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = applicationRelativePath(request);
        return !path.startsWith("/api/v1/") && !path.equals("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = boundedRequestId(request.getHeader(REQUEST_ID_HEADER));
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            LOG.info("query_completion requestId={} transport={} status={}", requestId, transport(request), response.getStatus());
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    private static String boundedRequestId(String candidate) {
        if (StringUtils.hasText(candidate) && candidate.length() <= MAX_REQUEST_ID_LENGTH && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    private static String transport(HttpServletRequest request) {
        String path = applicationRelativePath(request);
        return "/mcp".equals(path) ? "mcp" : "http";
    }

    private static String applicationRelativePath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (StringUtils.hasText(servletPath)) {
            return servletPath;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length()) : requestUri;
    }
}
