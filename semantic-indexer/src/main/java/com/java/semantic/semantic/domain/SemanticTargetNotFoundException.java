package com.java.semantic.semantic.domain;

import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.model.codefact.MethodTarget;

import java.util.Objects;

/** The requested source-qualified target has no syntax proof. */
public final class SemanticTargetNotFoundException extends RuntimeException implements ExpectedFailure {

    private final MethodTarget target;

    public SemanticTargetNotFoundException(MethodTarget target) {
        super("exact semantic target was not found");
        this.target = Objects.requireNonNull(target, "target is required");
    }

    public MethodTarget target() {
        return target;
    }
}
