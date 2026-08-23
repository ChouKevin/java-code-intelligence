package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

/** 固定 revision 中已 materialize 的 bounded 原始碼區段與可續讀位置 */
public record SourceRangeSegment(
        SourceRange location,
        String content,
        Optional<SourceRange> nextLocation,
        boolean contextTruncated) {

    public SourceRangeSegment {
        location = Objects.requireNonNull(location, "location is required");
        content = Objects.requireNonNull(content, "content is required");
        nextLocation = Objects.requireNonNull(nextLocation, "nextLocation is required");
    }
}
