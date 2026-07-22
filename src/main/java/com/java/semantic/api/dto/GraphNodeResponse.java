package com.java.semantic.api.dto;

import java.util.Objects;

/** One normalized node whose source availability and traversal progress are explicit. */
public record GraphNodeResponse(
        String nodeId,
        MethodTargetResponse target,
        String externalSymbol,
        String contentState,
        String traversalState,
        String methodBody,
        SourceRangeResponse declarationRange) {

    public GraphNodeResponse {
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        contentState = Objects.requireNonNull(contentState, "contentState is required");
        traversalState = Objects.requireNonNull(traversalState, "traversalState is required");
    }
}
