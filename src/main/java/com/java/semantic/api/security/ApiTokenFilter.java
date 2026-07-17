package com.java.semantic.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 以共用權杖保護所有端點
 *
 * 與 agent 的 ApiWriteProtectionFilter 不同:此處不豁免 GET
 * 本服務的讀取端點會回傳私有原始碼的衍生資訊,不存在無害的讀取
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String API_TOKEN_HEADER = "X-Api-Token";

    private static final String HEALTH_PATH = "/actuator/health";

    private final ApiSecurityProperties properties;

    public ApiTokenFilter(ApiSecurityProperties properties) {
        this.properties = properties;
    }

    /** health 供 compose 探測,是唯一豁免項 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.hasApiToken()) {
            writeError(response, HttpStatus.FORBIDDEN, "SEMANTIC_AUTH_DISABLED",
                    "semantic.api.api-token is not configured; the service refuses all traffic");
            return;
        }
        if (!properties.matchesApiToken(request.getHeader(API_TOKEN_HEADER))) {
            writeError(response, HttpStatus.UNAUTHORIZED, "SEMANTIC_UNAUTHORIZED",
                    API_TOKEN_HEADER + " header is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String errorCode, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String responseBody = """
                {"errorCode":"%s","message":"%s"}\
                """.formatted(errorCode, message);
        response.getWriter().write(responseBody);
    }
}
