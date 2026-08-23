package com.java.semantic.callgraph.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.domain.SemanticMethod;

import java.util.List;
import java.util.Objects;

/** 由語法證實的 implementation resolution 與 discovery metadata，供後續 selection 使用 */
public record ImplementationCandidate(
        SemanticMethod method,
        MethodTarget target,
        boolean primary,
        List<String> qualifiers,
        List<String> profiles) {

    public ImplementationCandidate {
        method = Objects.requireNonNull(method, "method is required");
        target = Objects.requireNonNull(target, "target is required");
        qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "qualifiers are required"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles are required"));
    }
}
