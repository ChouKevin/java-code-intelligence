package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

import java.util.Objects;

/** Server-enforced traversal coverage and depth-two hydration accounting. */
public record GraphTraversal(
        int requestedDepth,
        int expandedNodeCount,
        int nodeBudget,
        boolean rootDirectCallsComplete,
        GraphLimitReason limitReason) {

    public GraphTraversal {
        Assert.isTrue(requestedDepth == 1 || requestedDepth == 2,
                "requestedDepth must be one or two");
        Assert.isTrue(expandedNodeCount >= 0, "expandedNodeCount must not be negative");
        Assert.isTrue(nodeBudget >= 0, "nodeBudget must not be negative");
        Assert.isTrue(expandedNodeCount <= nodeBudget,
                "expandedNodeCount must not exceed nodeBudget");
        Assert.isTrue(rootDirectCallsComplete, "rootDirectCallsComplete is required");
        limitReason = Objects.requireNonNull(limitReason, "limitReason is required");
    }
}
