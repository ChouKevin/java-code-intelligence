package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** 穩定且不洩漏內部例外的 API 錯誤 */
public record ApiErrorResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String errorCode,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String message,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String expectedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String currentRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<MethodTargetPayload> candidates,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String requestId) {

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
