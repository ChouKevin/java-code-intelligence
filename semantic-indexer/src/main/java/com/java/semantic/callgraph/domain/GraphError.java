package com.java.semantic.callgraph.domain;

import org.springframework.util.Assert;

import java.util.Objects;

/** A descendant semantic-query failure retained after establishing a usable root fragment. */
public record GraphError(String code, String message, CallNodeId nodeId) {

    public GraphError {
        Assert.hasText(code, "code is required");
        Assert.hasText(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        Assert.isTrue("CHILD_SEMANTIC_QUERY_FAILED".equals(code), "graph error code is invalid");
    }
}
