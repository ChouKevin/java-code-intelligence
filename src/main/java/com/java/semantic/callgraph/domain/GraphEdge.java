package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;
import com.java.semantic.syntax.domain.MapperStatementIdentity;

/** One proven caller-to-callee edge for a concrete source call site. */
public record GraphEdge(
        CallNodeId callerNodeId,
        CallNodeId calleeNodeId,
        CallSiteRange callSite,
        String callExpression,
        ResolutionStrategy resolutionStrategy,
        List<String> evidence,
        List<MapperStatementIdentity> evidenceSourceIdentities) {

    public GraphEdge {
        callerNodeId = Objects.requireNonNull(callerNodeId, "callerNodeId is required");
        calleeNodeId = Objects.requireNonNull(calleeNodeId, "calleeNodeId is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        Assert.hasText(callExpression, "callExpression is required");
        resolutionStrategy = Objects.requireNonNull(resolutionStrategy, "resolutionStrategy is required");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
        evidenceSourceIdentities = List.copyOf(Objects.requireNonNull(
                evidenceSourceIdentities, "evidenceSourceIdentities is required"));
    }
}
