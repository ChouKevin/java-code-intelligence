package com.java.semantic.semantic.domain;

import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.identity.MethodTarget;

import java.util.Objects;

/** JDT LS could not prove the exact syntax-anchored declaration. */
public final class SemanticBindingUnresolvedException extends RuntimeException implements ExpectedFailure {

    private final MethodTarget target;

    public SemanticBindingUnresolvedException(MethodTarget target) {
        super("exact semantic target binding is unresolved");
        this.target = Objects.requireNonNull(target, "target is required");
    }

    public MethodTarget target() {
        return target;
    }
}
