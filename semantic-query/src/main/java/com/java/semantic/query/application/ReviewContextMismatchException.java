package com.java.semantic.query.application;

/** The requested side, revision, or fact context is outside immutable review membership. */
public final class ReviewContextMismatchException extends RuntimeException {
    public ReviewContextMismatchException() {
        super("REVIEW_CONTEXT_MISMATCH");
    }
}
