package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;

/** 已由 binding 證實的型別變數與其上界證據 */
public record TypeVariableReference(
        String writtenType,
        String variableName,
        List<TypeReference> upperBounds,
        boolean sourceDefined) implements TypeReference {

    public TypeVariableReference {
        writtenType = requireText(writtenType, "writtenType is required");
        variableName = requireText(variableName, "variableName is required");
        upperBounds = List.copyOf(Objects.requireNonNull(upperBounds, "upperBounds is required"));
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return Optional.empty();
    }

    private static String requireText(String value, String message) {
        String required = Objects.requireNonNull(value, message).trim();
        if (required.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return required;
    }
}
