package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;

/** var 或其他推論型別的原始碼證據 */
public record InferredTypeReference(String writtenType, boolean sourceDefined) implements TypeReference {

    public InferredTypeReference {
        writtenType = Objects.requireNonNull(writtenType, "writtenType is required").trim();
        if (writtenType.isBlank()) {
            throw new IllegalArgumentException("writtenType is required");
        }
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return Optional.empty();
    }
}
