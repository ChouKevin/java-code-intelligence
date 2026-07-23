package com.java.semantic.callgraph.domain;

import com.java.semantic.identity.MethodTarget;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Expected incomplete descendant semantic result at a specific root-reachable location. */
public record GraphWarning(
        String code,
        String message,
        CallNodeId nodeId,
        Optional<String> callExpression,
        Optional<CallSiteRange> callSite,
        List<MethodTarget> candidates) {

    public GraphWarning {
        Assert.hasText(code, "code is required");
        Assert.hasText(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        callExpression = Objects.requireNonNull(callExpression, "callExpression is required");
        callSite = Objects.requireNonNull(callSite, "callSite is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        Assert.isTrue(List.of("DESCENDANT_CALL_AMBIGUOUS", "DESCENDANT_CALL_UNRESOLVED", "INCOMING_CALLER_REJECTED", "NODE_BUDGET_REACHED")
                .contains(code), "graph warning code is invalid");
        if ("DESCENDANT_CALL_AMBIGUOUS".equals(code)) {
            Assert.isTrue(candidates.size() > 1, "ambiguous warning candidates are required");
        } else {
            Assert.isTrue(candidates.isEmpty(), "only ambiguous warnings may carry candidates");
        }
    }
}
