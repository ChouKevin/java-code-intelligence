package com.java.semantic.query.application;

import com.java.semantic.model.codefact.PublishedEntryPoint;
import com.java.semantic.model.query.CurrentGeneration;
import java.util.List;
import java.util.Objects;

/** One deterministic page of authorized entry points from a current generation. */
public record PublishedEntryPointResult(CurrentGeneration generation, List<PublishedEntryPoint> entryPoints,
                                        long totalCount, boolean hasMore) {
    public PublishedEntryPointResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        entryPoints = List.copyOf(Objects.requireNonNull(entryPoints, "entry points are required"));
        if (totalCount < entryPoints.size()) {
            throw new IllegalArgumentException("total count must include returned entry points");
        }
    }
}
