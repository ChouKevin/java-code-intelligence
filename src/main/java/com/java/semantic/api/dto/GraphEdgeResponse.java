package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** A proven caller-to-callee edge for one call site. */
public record GraphEdgeResponse(
        String callerNodeId,
        String calleeNodeId,
        SourceRangeResponse callSite,
        String callExpression,
        String resolutionStrategy,
        String category,
        List<String> evidence) {

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
