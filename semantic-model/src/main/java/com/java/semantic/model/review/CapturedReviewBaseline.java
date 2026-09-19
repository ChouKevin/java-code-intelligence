package com.java.semantic.model.review;

import com.java.semantic.model.index.PublishedGenerationPointer;
import java.time.Instant;
import java.util.Objects;

/** The published pointer captured when review admission succeeds. */
public record CapturedReviewBaseline(PublishedGenerationPointer pointer, Instant capturedAt) {

    public CapturedReviewBaseline {
        pointer = Objects.requireNonNull(pointer, "captured generation pointer is required");
        capturedAt = Objects.requireNonNull(capturedAt, "review baseline capture time is required");
    }
}
