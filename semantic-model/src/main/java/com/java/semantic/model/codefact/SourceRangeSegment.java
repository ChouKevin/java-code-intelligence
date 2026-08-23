package com.java.semantic.model.codefact;

import java.util.Objects;
import java.util.Optional;

public record SourceRangeSegment(
        SourceRange location,
        String content,
        Optional<SourceRange> nextLocation,
        boolean contextTruncated) {

    public SourceRangeSegment {
        location = Objects.requireNonNull(location, "location is required");
        content = Objects.requireNonNull(content, "content is required");
        nextLocation = Objects.requireNonNull(nextLocation, "next location is required");
    }
}
