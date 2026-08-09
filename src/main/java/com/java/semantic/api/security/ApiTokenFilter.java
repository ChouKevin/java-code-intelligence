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
 * 以共用權杖保護端點,僅公開固定的文件和健康檢查路徑
 *
 * 與 agent 的 ApiWriteProtectionFilter 不同:此處不豁免 GET
 * 本服務的讀取端點會回傳私有原始碼的衍生資訊,不存在無害的讀取;
 * Swagger UI 僅讀取版本化的靜態 OpenAPI 契約
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String API_TOKEN_HEADER = "X-Api-Token";
    public static final String AUTH_ERROR_CODE_ATTRIBUTE = "semantic.apiAuthenticationErrorCode";

    private static final String HEALTH_PATH = "/actuator/health";
    private static final String OPENAPI_CONTRACT_PATH = "/openapi/semantic-api-v1.yaml";
    private static final String SWAGGER_UI_ENTRY_PATH = "/swagger-ui.html";
    private static final String SWAGGER_UI_PATH_PREFIX = "/swagger-ui/";
    private static final String SWAGGER_CONFIG_PATH = "/v3/api-docs/swagger-config";
    private final ApiSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public ApiTokenFilter(ApiSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Health and the fixed documentation surface are public; every other path remains fail-closed. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return HEALTH_PATH.equals(requestUri)
                || OPENAPI_CONTRACT_PATH.equals(requestUri)
                || SWAGGER_UI_ENTRY_PATH.equals(requestUri)
                || requestUri.startsWith(SWAGGER_UI_PATH_PREFIX)
                || SWAGGER_CONFIG_PATH.equals(requestUri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.hasApiToken()) {
            writeError(request, response, HttpStatus.FORBIDDEN, "SEMANTIC_AUTH_DISABLED",
                    "semantic.api.api-token is not configured; the service refuses all traffic");
            return;
        }
        if (!properties.matchesApiToken(request.getHeader(API_TOKEN_HEADER))) {
            writeError(request, response, HttpStatus.UNAUTHORIZED, "SEMANTIC_UNAUTHORIZED",
                    API_TOKEN_HEADER + " header is required");
            return;
        }
        filterChain.doFilter(request, response);
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
