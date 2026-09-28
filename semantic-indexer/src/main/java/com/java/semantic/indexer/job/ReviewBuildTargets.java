package com.java.semantic.indexer.job;

import com.java.semantic.model.review.ReviewSide;

import java.util.Optional;
import java.util.Objects;

/** Immutable generation targets reserved when the worker fixes the Git endpoints. */
public record ReviewBuildTargets(Optional<IndexJobTarget> before, IndexJobTarget after) {
    public ReviewBuildTargets {
        before = Objects.requireNonNull(before, "review before target is required");
        after = Objects.requireNonNull(after, "review after target is required");
        if (before.isPresent() && (before.orElseThrow().generationId().equals(after.generationId())
                || before.orElseThrow().generation() == after.generation())) {
            throw new IllegalArgumentException("review targets must reserve distinct generations");
        }
    }

    public IndexJobTarget target(ReviewSide side) {
        return switch (Objects.requireNonNull(side, "review side is required")) {
            case BEFORE -> before.orElseThrow(() -> new IllegalArgumentException("empty tree has no semantic target"));
            case AFTER -> after;
        };
    }
}
