package com.java.semantic.api.dto;

import java.util.List;

/** 穩定且不洩漏內部例外的 API 錯誤 */
public record ApiErrorResponse(
        String errorCode,
        String message,
        String repoId,
        String expectedRevision,
        String currentRevision,
        MethodTargetResponse target,
        List<MethodTargetResponse> candidates,
        String requestId) {

    public ApiErrorResponse {
        candidates = List.copyOf(candidates);
    }

    public static ApiErrorResponse of(String errorCode, String message, String requestId) {
        return new ApiErrorResponse(
                errorCode, message, null, null, null, null, List.of(), requestId);
    }

    public static ApiErrorResponse withContext(
            String errorCode,
            String message,
            String repoId,
            String expectedRevision,
            String currentRevision,
            MethodTargetResponse target,
            List<MethodTargetResponse> candidates,
            String requestId) {
        return new ApiErrorResponse(
                errorCode, message, repoId, expectedRevision, currentRevision, target, candidates, requestId);
    }
}
