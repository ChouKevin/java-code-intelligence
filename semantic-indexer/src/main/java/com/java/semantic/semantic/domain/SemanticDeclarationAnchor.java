package com.java.semantic.semantic.domain;

import com.java.semantic.identity.MethodTarget;

import java.util.Objects;

/** Syntax-proven declaration position for an exact method target. */
public record SemanticDeclarationAnchor(MethodTarget target, SemanticPosition namePosition) {

    public SemanticDeclarationAnchor {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(namePosition, "namePosition is required");
    }
}
