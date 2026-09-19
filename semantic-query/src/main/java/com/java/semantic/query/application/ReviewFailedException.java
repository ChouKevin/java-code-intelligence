package com.java.semantic.query.application;

/** The requested review ended before immutable evidence was published. */
public final class ReviewFailedException extends RuntimeException {
    public ReviewFailedException() {
        super("REVIEW_FAILED");
    }
}
