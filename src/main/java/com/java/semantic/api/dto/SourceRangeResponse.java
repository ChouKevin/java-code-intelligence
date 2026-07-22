package com.java.semantic.api.dto;

import org.springframework.util.Assert;

import java.util.Objects;

/** Start-inclusive, end-exclusive source range using zero-based UTF-16 positions. */
public record SourceRangeResponse(
        String sourceFile,
        PositionResponse start,
        PositionResponse end) {

    public SourceRangeResponse {
        Assert.hasText(sourceFile, "sourceFile must not be blank");
        start = Objects.requireNonNull(start, "start is required");
        end = Objects.requireNonNull(end, "end is required");
    }
}
