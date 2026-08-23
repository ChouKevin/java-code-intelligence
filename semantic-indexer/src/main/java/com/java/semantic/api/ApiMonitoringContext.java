package com.java.semantic.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Objects;
import java.util.Optional;

/** 保存單一 HTTP 請求已反序列化的監控資料 */
public final class ApiMonitoringContext {

    public static final String ATTRIBUTE = "semantic.apiMonitoringContext";

    private Optional<Object> requestBody = Optional.empty();
    private Optional<Object> responseBody = Optional.empty();
    private Optional<String> errorCode = Optional.empty();

    public static ApiMonitoringContext initialize(HttpServletRequest request) {
        HttpServletRequest servletRequest = Objects.requireNonNull(request, "request is required");
        ApiMonitoringContext context = new ApiMonitoringContext();
        servletRequest.setAttribute(ATTRIBUTE, context);
        return context;
    }

    public static Optional<ApiMonitoringContext> find(HttpServletRequest request) {
        HttpServletRequest servletRequest = Objects.requireNonNull(request, "request is required");
        Object attribute = servletRequest.getAttribute(ATTRIBUTE);
        if (attribute instanceof ApiMonitoringContext context) {
            return Optional.of(context);
        }
        return Optional.empty();
    }

    public static Optional<ApiMonitoringContext> findCurrent() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return find(servletRequestAttributes.getRequest());
        }
        return Optional.empty();
    }

    public void recordRequestBody(Object body) {
        requestBody = Optional.ofNullable(body);
    }

    public void recordResponseBody(Object body) {
        responseBody = Optional.ofNullable(body);
    }

    public void recordErrorCode(String responseErrorCode) {
        errorCode = Optional.of(Objects.requireNonNull(responseErrorCode, "responseErrorCode is required"));
    }

    public Optional<Object> requestBody() {
        return requestBody;
    }

    public Optional<Object> responseBody() {
        return responseBody;
    }

    public Optional<String> errorCode() {
        return errorCode;
    }

}
