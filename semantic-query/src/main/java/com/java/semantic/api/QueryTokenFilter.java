package com.java.semantic.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

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
        String requestPath = request.getRequestURI();
        return !requestPath.startsWith("/v1/") && !requestPath.equals("/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.hasApiToken()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "query access is disabled");
            return;
        }
        if (!properties.matches(request.getHeader(TOKEN_HEADER))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "query token is required");
            return;
        }
        chain.doFilter(request, response);
    }
}
