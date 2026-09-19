package com.java.semantic.query.application;

/** The requested review is known but has not published its immutable membership. */
public final class ReviewNotReadyException extends RuntimeException {
    public ReviewNotReadyException() {
        super("REVIEW_NOT_READY");
    }
}
