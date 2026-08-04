package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** mapper statement 對應之完整 method target 與狀態限定 follow-up */
public record MapperMethodCandidateResponse(
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.NESTED)
        List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MapperMethodCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
