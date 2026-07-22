package com.java.semantic.api.dto;

import java.util.Objects;

/** Descendant failure captured after a usable outgoing graph fragment was established. */
public record GraphErrorResponse(String code, String message, String nodeId) {

    public GraphErrorResponse {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
    }
}
