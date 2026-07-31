package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import org.springframework.util.Assert;

import java.util.Objects;
import java.util.Set;

/** Traversal limits and coverage for one outgoing graph fragment. */
public record GraphTraversalResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int requestedDepth,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int expandedNodeCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int nodeBudget,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean rootDirectCallsComplete,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String limitReason) {

    private static final Set<String> LIMIT_REASONS = Set.of("NONE", "NODE_BUDGET");

    public GraphTraversalResponse {
        Assert.isTrue(requestedDepth >= 1 && requestedDepth <= 2, "requestedDepth must be between 1 and 2");
        Assert.isTrue(expandedNodeCount >= 0, "expandedNodeCount must not be negative");
        Assert.isTrue(nodeBudget >= 0, "nodeBudget must not be negative");
        limitReason = Objects.requireNonNull(limitReason, "limitReason is required");
        Assert.isTrue(LIMIT_REASONS.contains(limitReason), "limitReason must be NONE or NODE_BUDGET");
    }
}
