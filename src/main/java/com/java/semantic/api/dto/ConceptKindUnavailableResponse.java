package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 已宣告但未啟用概念種類的固定 client-safe 錯誤 */
public record ConceptKindUnavailableResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String errorCode,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String message,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> unavailableKinds,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> supportedKinds,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String requestId) {

    public ConceptKindUnavailableResponse {
        errorCode = Objects.requireNonNull(errorCode, "errorCode is required");
        message = Objects.requireNonNull(message, "message is required");
        unavailableKinds = List.copyOf(Objects.requireNonNull(unavailableKinds, "unavailableKinds is required"));
        supportedKinds = List.copyOf(Objects.requireNonNull(supportedKinds, "supportedKinds is required"));
        requestId = Objects.requireNonNull(requestId, "requestId is required");
    }
}
