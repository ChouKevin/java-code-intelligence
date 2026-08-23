package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** API representation of an exact method-target resolution outcome. */
public record MethodTargetResolutionResponse(
        @MonitoringField(MonitoringMode.VALUE) String status,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.SIZE) List<MethodTargetPayload> candidates,
        @MonitoringField(MonitoringMode.VALUE) String reasonCode,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

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
