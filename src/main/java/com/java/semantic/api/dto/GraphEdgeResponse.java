package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;

/** A proven caller-to-callee edge for one call site. */
public record GraphEdgeResponse(
        @MonitoringField(MonitoringMode.VALUE) String callerNodeId,
        @MonitoringField(MonitoringMode.VALUE) String calleeNodeId,
        @MonitoringField(MonitoringMode.NESTED) SourceRangePayload callSite,
        @MonitoringField(MonitoringMode.OMIT) String callExpression,
        @MonitoringField(MonitoringMode.VALUE) String resolutionStrategy,
        @MonitoringField(MonitoringMode.VALUE) String category,
        @MonitoringField(MonitoringMode.SIZE) List<String> evidence,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public GraphEdgeResponse {
        callerNodeId = Objects.requireNonNull(callerNodeId, "callerNodeId is required");
        calleeNodeId = Objects.requireNonNull(calleeNodeId, "calleeNodeId is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        callExpression = Objects.requireNonNull(callExpression, "callExpression is required");
        resolutionStrategy = Objects.requireNonNull(resolutionStrategy, "resolutionStrategy is required");
        category = Objects.requireNonNull(category, "category is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps, "availableFollowUps is required"));
    }
}
