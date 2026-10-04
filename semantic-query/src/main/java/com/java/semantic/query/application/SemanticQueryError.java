package com.java.semantic.query.application;

import java.util.Objects;

/** Safe transport-neutral source application failure body. */
public record SemanticQueryError(String code, String message) {

    public SemanticQueryError {
        code = Objects.requireNonNull(code, "error code");
        message = Objects.requireNonNull(message, "error message");
    }
}
