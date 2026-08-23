package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;

/** Expected incomplete semantic outcome, including every exact candidate where applicable. */
public record GraphWarningResponse(
        @MonitoringField(MonitoringMode.VALUE) String code,
        @MonitoringField(MonitoringMode.SIZE) String message,
        @MonitoringField(MonitoringMode.VALUE) String nodeId,
        @MonitoringField(MonitoringMode.OMIT) String callExpression,
        @MonitoringField(MonitoringMode.NESTED) SourceRangePayload callSite,
        @MonitoringField(MonitoringMode.SIZE) List<MethodTargetPayload> candidates,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public GraphWarningResponse {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }

    public GraphWarningResponse(
            String code,
            String message,
            String nodeId,
            String callExpression,
            SourceRangePayload callSite,
            List<MethodTargetPayload> candidates) {
        this(code, message, nodeId, callExpression, callSite, candidates, List.of());
    }
}
