package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;

/** Java 基本型別的原始碼證據 */
public record PrimitiveTypeReference(String writtenType) implements TypeReference {

    public PrimitiveTypeReference {
        writtenType = Objects.requireNonNull(writtenType, "writtenType is required").trim();
        if (writtenType.isBlank()) {
            throw new IllegalArgumentException("writtenType is required");
        }
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return Optional.empty();
    }

    @Override
    public boolean sourceDefined() {
        return false;
    }
}
