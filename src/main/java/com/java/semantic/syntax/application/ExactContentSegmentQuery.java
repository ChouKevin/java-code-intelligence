package com.java.semantic.syntax.application;

import java.util.Objects;

/** 以原始 exact authority 與 content reference 讀取一個零起算 segment 的查詢 */
public record ExactContentSegmentQuery(
        ExactContentQuery contentQuery,
        String contentRef,
        int segmentIndex) {

    public ExactContentSegmentQuery {
        contentQuery = Objects.requireNonNull(contentQuery, "contentQuery is required");
        contentRef = Objects.requireNonNull(contentRef, "contentRef is required");
        if (contentRef.isBlank()) {
            throw new IllegalArgumentException("contentRef is required");
        }
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must not be negative");
        }
    }
}
