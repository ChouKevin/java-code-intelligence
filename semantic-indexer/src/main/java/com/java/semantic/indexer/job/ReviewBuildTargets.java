package com.java.semantic.indexer.job;

import com.java.semantic.model.review.ReviewSide;

import java.util.Objects;

/** Immutable generation targets reserved at review admission. */
public record ReviewBuildTargets(IndexJobTarget a, IndexJobTarget b) {
    public ReviewBuildTargets {
        a = Objects.requireNonNull(a, "review target A is required");
        b = Objects.requireNonNull(b, "review target B is required");
        if (a.generationId().equals(b.generationId()) || a.generation() == b.generation()) {
            throw new IllegalArgumentException("review targets must reserve distinct generations");
        }
    }

    public IndexJobTarget target(ReviewSide side) {
        return switch (Objects.requireNonNull(side, "review side is required")) {
            case A -> a;
            case B -> b;
        };
    }
}
