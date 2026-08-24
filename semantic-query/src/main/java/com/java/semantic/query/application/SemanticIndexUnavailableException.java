package com.java.semantic.query.application;

import java.util.Objects;

public final class SemanticIndexUnavailableException extends RuntimeException {
    public SemanticIndexUnavailableException(RuntimeException cause) {
        super("SEMANTIC_INDEX_UNAVAILABLE", Objects.requireNonNull(cause, "storage failure is required"));
    }
}
