package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;

public record EventListenerResult(CurrentGeneration generation, EventListenerQuery query,
                                  List<EventListenerCandidate> candidates, long totalCount, boolean hasMore) {

    public EventListenerResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        query = Objects.requireNonNull(query, "query is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        ModelValidation.require(totalCount >= candidates.size(), "total count must cover candidates");
    }
}
