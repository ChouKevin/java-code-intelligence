package com.java.semantic.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Objects;

/** Protects all Query read transports while allowing health checks to remain unauthenticated. */
public final class QueryTokenFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Api-Token";
    private final QuerySecurityProperties properties;

    public QueryTokenFilter(QuerySecurityProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties are required");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = applicationRelativePath(request);
        return !requestPath.startsWith("/v1/") && !requestPath.equals("/mcp");
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.hasApiToken()) {
            writeFailure(response, HttpServletResponse.SC_FORBIDDEN, "QUERY_ACCESS_DISABLED");
            return;
        }
        if (!properties.matches(request.getHeader(TOKEN_HEADER))) {
            writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED, "QUERY_TOKEN_REQUIRED");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void writeFailure(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"retryable\":false}");
    }
}
