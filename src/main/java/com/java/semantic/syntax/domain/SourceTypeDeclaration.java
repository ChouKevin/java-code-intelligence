package com.java.semantic.syntax.domain;

import com.java.semantic.identity.SourceTypeIdentity;

import java.util.Objects;

/** 來源型別的宣告與定位證據 */
public record SourceTypeDeclaration(
        SourceTypeIdentity identity,
        SourceTypeKind kind,
        boolean abstractType,
        SourceSlice source) {

    public SourceTypeDeclaration {
        identity = Objects.requireNonNull(identity, "identity is required");
        kind = Objects.requireNonNull(kind, "kind is required");
        source = Objects.requireNonNull(source, "source is required");
    }
}
