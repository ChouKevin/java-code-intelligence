package com.java.semantic.api.security;

import tools.jackson.databind.ObjectMapper;
import com.java.semantic.api.RequestCorrelationFilter;
import com.java.semantic.api.ApiMonitoringContext;
import com.java.semantic.api.dto.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 以共用權杖保護所有端點
 *
 * 與 agent 的 ApiWriteProtectionFilter 不同:此處不豁免 GET
 * 本服務的讀取端點會回傳私有原始碼的衍生資訊,不存在無害的讀取
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String API_TOKEN_HEADER = "X-Api-Token";
    public static final String AUTH_ERROR_CODE_ATTRIBUTE = "semantic.apiAuthenticationErrorCode";

    private static final String HEALTH_PATH = "/actuator/health";
    private static final String INDEX_ADMIN_PATH = "/index/";
    private final ApiSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiTokenFilter(ApiSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** health 供 compose 探測,是唯一豁免項 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return HEALTH_PATH.equals(applicationPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean adminRequest = applicationPath(request).startsWith(INDEX_ADMIN_PATH);
        if (adminRequest && !properties.hasAdminToken()) {
            writeError(request, response, HttpStatus.FORBIDDEN, "SEMANTIC_ADMIN_AUTH_DISABLED",
                    "semantic.api.admin-token is not configured; the service refuses index administration");
            return;
        }
        if (!adminRequest && !properties.hasApiToken()) {
            writeError(request, response, HttpStatus.FORBIDDEN, "SEMANTIC_AUTH_DISABLED",
                    "semantic.api.api-token is not configured; the service refuses all traffic");
            return;
        }
        boolean authorized = adminRequest
                ? properties.matchesAdminToken(request.getHeader(API_TOKEN_HEADER))
                : properties.matchesApiToken(request.getHeader(API_TOKEN_HEADER));
        if (!authorized) {
            writeError(request, response, HttpStatus.UNAUTHORIZED, "SEMANTIC_UNAUTHORIZED",
                    API_TOKEN_HEADER + " header is required");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String applicationPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestUri = request.getRequestURI();
        String path = requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : requestUri;
        return path.replaceAll(";[^/]*", "");
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String errorCode,
            String message)
            throws IOException {
        request.setAttribute(AUTH_ERROR_CODE_ATTRIBUTE, errorCode);
        ApiMonitoringContext.find(request).ifPresent(context -> context.recordErrorCode(errorCode));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requestId = Objects.toString(
                request.getAttribute(RequestCorrelationFilter.REQUEST_ID_ATTRIBUTE), "");
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.withContext(
                errorCode,
                message,
                null,
                null,
                null,
                null,
                List.of(),
                requestId));
    }
}
