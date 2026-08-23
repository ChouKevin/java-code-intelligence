package com.java.semantic.semantic.domain;

import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.model.codefact.MethodTarget;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** Syntax proof identifies more than one possible exact declaration. */
public final class SemanticBindingAmbiguousException extends RuntimeException implements ExpectedFailure {

    private final MethodTarget target;
    private final List<MethodTarget> candidates;

    public SemanticBindingAmbiguousException(MethodTarget target, List<MethodTarget> candidates) {
        super("exact semantic target binding is ambiguous");
        this.target = Objects.requireNonNull(target, "target is required");
        this.candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        Assert.isTrue(this.candidates.size() > 1, "ambiguous candidates are required");
    }

    public MethodTarget target() {
        return target;
    }

    public List<MethodTarget> candidates() {
        return candidates;
    }
}
