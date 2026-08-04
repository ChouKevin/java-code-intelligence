package com.java.semantic.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 拒絕帶有 Origin header 的 MCP browser request */
public final class McpOriginFilter extends OncePerRequestFilter {

    private static final String ORIGIN_HEADER = "Origin";
    private static final String ERROR_BODY =
            "{\"errorCode\":\"MCP_ORIGIN_FORBIDDEN\",\"message\":\"Origin header is not allowed\"}";

    private final String mcpEndpoint;

    public McpOriginFilter(String mcpEndpoint) {
        Assert.hasText(mcpEndpoint, "mcpEndpoint is required");
        this.mcpEndpoint = mcpEndpoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !mcpEndpoint.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (Collections.list(request.getHeaders(ORIGIN_HEADER)).isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ERROR_BODY);
    }
}
