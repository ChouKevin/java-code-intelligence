package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** Expected incomplete semantic outcome, including every exact candidate where applicable. */
public record GraphWarningResponse(
        String code,
        String message,
        String nodeId,
        String callExpression,
        SourceRangeResponse callSite,
        List<MethodTargetResponse> candidates) {

    public GraphWarningResponse {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
    }
}
