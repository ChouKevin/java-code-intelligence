package com.java.semantic.query.application;

import java.util.Objects;

/** Safe transport-neutral source application failure body. */
public record SemanticQueryError(String code, String message,
        java.util.Optional<com.java.semantic.model.source.SourceReadContract.SummaryOutcome> reason) {

    public SemanticQueryError {
        code = Objects.requireNonNull(code, "error code");
        message = Objects.requireNonNull(message, "error message");
        reason = Objects.requireNonNull(reason, "summary reason");
    }
}
