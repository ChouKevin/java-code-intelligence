package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** A proven caller-to-callee edge for one call site. */
public record GraphEdgeResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String callerNodeId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String calleeNodeId,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse callSite,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String callExpression,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String resolutionStrategy,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String category,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> evidence) {

    public GraphEdgeResponse {
        callerNodeId = Objects.requireNonNull(callerNodeId, "callerNodeId is required");
        calleeNodeId = Objects.requireNonNull(calleeNodeId, "calleeNodeId is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        callExpression = Objects.requireNonNull(callExpression, "callExpression is required");
        resolutionStrategy = Objects.requireNonNull(resolutionStrategy, "resolutionStrategy is required");
        category = Objects.requireNonNull(category, "category is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
    }
}
