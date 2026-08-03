package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** API representation of an exact method-target resolution outcome. */
public record MethodTargetResolutionResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<MethodTargetPayload> candidates,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String reasonCode,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MethodTargetResolutionResponse {
        status = Objects.requireNonNull(status, "status is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }

    public MethodTargetResolutionResponse(
            String status,
            MethodTargetPayload target,
            List<MethodTargetPayload> candidates,
            String reasonCode) {
        this(status, target, candidates, reasonCode, List.of());
    }
}
