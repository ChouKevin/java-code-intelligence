package com.java.semantic.query.application;

/** The requested review is not visible to this reader. */
public final class ReviewNotFoundException extends RuntimeException {
    public ReviewNotFoundException() {
        super("REVIEW_NOT_FOUND");
    }
}
