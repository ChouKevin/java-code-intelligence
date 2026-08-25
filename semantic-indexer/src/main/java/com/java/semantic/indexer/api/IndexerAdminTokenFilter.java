package com.java.semantic.indexer.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Objects;

/** Protects the only Indexer transport surface without importing Query authentication code. */
public final class IndexerAdminTokenFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Api-Token";
    private final IndexerAdminSecurityProperties properties;

    public IndexerAdminTokenFilter(IndexerAdminSecurityProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties are required");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !applicationRelativePath(request).startsWith("/index/");
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
        if (!properties.hasAdminToken()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "index administration is disabled");
            return;
        }
        if (!properties.matches(request.getHeader(TOKEN_HEADER))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "admin token is required");
            return;
        }
        chain.doFilter(request, response);
    }
}
