package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** API representation of an exact method-target resolution outcome. */
public record MethodTargetResolutionResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<MethodTargetResponse> candidates,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String reasonCode) {

    public MethodTargetResolutionResponse {
        status = Objects.requireNonNull(status, "status is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode is required");
    }
}
