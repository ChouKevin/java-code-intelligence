package com.java.semantic.callgraph.application;

import com.java.semantic.semantic.domain.SemanticMethod;

import java.util.List;
import java.util.Objects;

/** Spring implementation candidate with only source-extracted bean evidence. */
public record ImplementationCandidate(
        SemanticMethod method,
        boolean primary,
        List<String> qualifiers,
        List<String> profiles) {

    public ImplementationCandidate {
        Objects.requireNonNull(method, "method is required");
        qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "qualifiers are required"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles are required"));
    }
}
