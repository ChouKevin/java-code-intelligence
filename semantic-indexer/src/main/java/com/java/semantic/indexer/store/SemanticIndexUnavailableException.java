package com.java.semantic.indexer.store;

/** Retryable persistence-boundary failure; callers must not fall back to analysis. */
public final class SemanticIndexUnavailableException extends RuntimeException {
    public SemanticIndexUnavailableException(String message, RuntimeException cause) { super(message, cause); }
}
