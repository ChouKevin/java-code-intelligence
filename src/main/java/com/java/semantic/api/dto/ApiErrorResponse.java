package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;

/** 穩定且不洩漏內部例外的 API 錯誤 */
public record ApiErrorResponse(
        @MonitoringField(MonitoringMode.VALUE) String errorCode,
        @MonitoringField(MonitoringMode.SIZE) String message,
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String expectedRevision,
        @MonitoringField(MonitoringMode.VALUE) String currentRevision,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.SIZE) List<MethodTargetPayload> candidates,
        @MonitoringField(MonitoringMode.VALUE) String requestId) {

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
            MethodTargetPayload target,
            List<MethodTargetPayload> candidates,
            String requestId) {
        return new ApiErrorResponse(
                errorCode, message, repoId, expectedRevision, currentRevision, target, candidates, requestId);
    }
}
