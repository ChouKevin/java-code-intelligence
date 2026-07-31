package com.java.semantic.syntax.application;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/** exact content 的單一 Unicode code-point-safe segment 與下一段完整查詢 */
public record ExactContentSegment(
        String contentRef,
        int segmentIndex,
        int segmentCount,
        int utf8ByteCount,
        String content,
        Optional<ExactContentSegmentQuery> nextSegmentQuery) {

    public ExactContentSegment {
        contentRef = Objects.requireNonNull(contentRef, "contentRef is required");
        content = Objects.requireNonNull(content, "content is required");
        nextSegmentQuery = Objects.requireNonNull(nextSegmentQuery, "nextSegmentQuery is required");
        if (contentRef.isBlank()) {
            throw new IllegalArgumentException("contentRef is required");
        }
        if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount) {
            throw new IllegalArgumentException("segment metadata is invalid");
        }
        if (utf8ByteCount != content.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("segment byte count is inconsistent");
        }
        if (segmentIndex + 1 < segmentCount && nextSegmentQuery.isEmpty()) {
            throw new IllegalArgumentException("non-final segment requires next query");
        }
        if (segmentIndex + 1 == segmentCount && nextSegmentQuery.isPresent()) {
            throw new IllegalArgumentException("final segment must not contain next query");
        }
    }
}
