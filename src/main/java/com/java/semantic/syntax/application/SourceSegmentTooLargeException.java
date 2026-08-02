package com.java.semantic.syntax.application;

/** Java source segment 的精確要求範圍超過 byte bound */
public final class SourceSegmentTooLargeException extends RuntimeException {

    public SourceSegmentTooLargeException() {
        super("Exact Java source segment exceeds the byte limit");
    }
}
