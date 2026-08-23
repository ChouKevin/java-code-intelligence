package com.java.semantic.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.api.dto.ConceptKindUnavailableResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 在 HTTP 回應寫出前保存 DTO 與固定錯誤代碼 */
@ControllerAdvice
public final class ApiMonitoringResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        ApiMonitoringContext.findCurrent().ifPresent(context -> recordResponse(context, body));
        return body;
    }

    private void recordResponse(ApiMonitoringContext context, Object body) {
        context.recordResponseBody(body);
        if (body instanceof ApiErrorResponse errorResponse) {
            context.recordErrorCode(errorResponse.errorCode());
        } else if (body instanceof ConceptKindUnavailableResponse errorResponse) {
            context.recordErrorCode(errorResponse.errorCode());
        }
    }
}
