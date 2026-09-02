package com.java.semantic.query.application;

import java.util.Objects;
import java.util.Optional;

/** Safe, transport-neutral application failure body for Semantic Query. */
public record SemanticQueryError(String code, String message, boolean retryable, Optional<String> currentRevision) {

    public SemanticQueryError {
        code = Objects.requireNonNull(code, "error code is required");
        message = Objects.requireNonNull(message, "error message is required");
        currentRevision = Objects.requireNonNull(currentRevision, "current revision is required");
    }
}
