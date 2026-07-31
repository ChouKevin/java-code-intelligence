package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** mapper statement 對應之完整 method target 與狀態限定 follow-up */
public record MapperMethodCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
        @ApiMonitoringField(ApiMonitoringMode.SIZE)
        List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MapperMethodCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
