package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;

/** 含有元素型別與維度的陣列型別證據 */
public record ArrayTypeReference(
        String writtenType,
        TypeReference elementType,
        int dimensions) implements TypeReference {

    public ArrayTypeReference {
        writtenType = Objects.requireNonNull(writtenType, "writtenType is required").trim();
        if (writtenType.isBlank()) {
            throw new IllegalArgumentException("writtenType is required");
        }
        elementType = Objects.requireNonNull(elementType, "elementType is required");
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return elementType.resolvedNamedType();
    }

    @Override
    public Optional<String> resolvedTypeName() {
        return elementType.resolvedTypeName().map(typeName -> typeName + "[]".repeat(dimensions));
    }

    @Override
    public boolean sourceDefined() {
        return elementType.sourceDefined();
    }
}
