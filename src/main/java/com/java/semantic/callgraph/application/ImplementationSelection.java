package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.semantic.domain.SemanticMethod;

import java.util.List;
import java.util.Objects;

/** Deterministic Spring selection result and its explainable evidence warning. */
public record ImplementationSelection(
        List<SemanticMethod> candidates,
        ResolutionStrategy strategy,
        List<String> warnings) {

    public ImplementationSelection {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        Objects.requireNonNull(strategy, "strategy is required");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings are required"));
    }
}
