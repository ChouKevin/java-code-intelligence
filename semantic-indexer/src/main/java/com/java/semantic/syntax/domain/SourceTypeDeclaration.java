package com.java.semantic.syntax.domain;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.model.codefact.SourceTypeIdentity;

import java.util.Objects;

/** 來源型別的宣告與定位證據 */
public record SourceTypeDeclaration(
        SourceTypeIdentity identity,
        SourceTypeKind kind,
        boolean abstractType,
        SourceRange declarationLocation) {

    public SourceTypeDeclaration {
        identity = Objects.requireNonNull(identity, "identity is required");
        kind = Objects.requireNonNull(kind, "kind is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        if (!identity.sourceFile().equals(declarationLocation.sourceFile())) {
            throw new IllegalArgumentException("declarationLocation sourceFile must match identity");
        }
    }
}
