package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 已宣告但未啟用概念種類的固定 client-safe 錯誤 */
public record ConceptKindUnavailableResponse(
        @MonitoringField(MonitoringMode.VALUE) String errorCode,
        @MonitoringField(MonitoringMode.SIZE) String message,
        @MonitoringField(MonitoringMode.SIZE) List<String> unavailableKinds,
        @MonitoringField(MonitoringMode.SIZE) List<String> supportedKinds,
        @MonitoringField(MonitoringMode.VALUE) String requestId) {

    public ConceptKindUnavailableResponse {
        errorCode = Objects.requireNonNull(errorCode, "errorCode is required");
        message = Objects.requireNonNull(message, "message is required");
        unavailableKinds = List.copyOf(Objects.requireNonNull(unavailableKinds, "unavailableKinds is required"));
        supportedKinds = List.copyOf(Objects.requireNonNull(supportedKinds, "supportedKinds is required"));
        requestId = Objects.requireNonNull(requestId, "requestId is required");
    }
}
