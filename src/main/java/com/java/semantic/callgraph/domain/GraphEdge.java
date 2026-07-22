package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** One proven caller-to-callee edge for a concrete source call site. */
public record GraphEdge(
        CallNodeId callerNodeId,
        CallNodeId calleeNodeId,
        CallSiteRange callSite,
        String callExpression,
        ResolutionStrategy resolutionStrategy,
        double confidence,
        List<String> evidence) {

    public GraphEdge {
        callerNodeId = Objects.requireNonNull(callerNodeId, "callerNodeId is required");
        calleeNodeId = Objects.requireNonNull(calleeNodeId, "calleeNodeId is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        Assert.hasText(callExpression, "callExpression is required");
        resolutionStrategy = Objects.requireNonNull(resolutionStrategy, "resolutionStrategy is required");
        Assert.isTrue(confidence >= 0.0d && confidence <= 1.0d,
                "confidence must be between zero and one");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
    }
}
