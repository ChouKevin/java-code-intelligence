package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;

/** 單一簡單、限定或名稱限定型別的證據 */
public record NamedTypeReference(
        String writtenType,
        String simpleTypeName,
        Optional<JavaTypeIdentity> resolvedNamedType,
        boolean sourceDefined) implements NominalTypeReference {

    public NamedTypeReference {
        writtenType = requireText(writtenType, "writtenType is required");
        simpleTypeName = requireText(simpleTypeName, "simpleTypeName is required");
        resolvedNamedType = Objects.requireNonNull(resolvedNamedType, "resolvedNamedType is required");
    }

    private static String requireText(String value, String message) {
        String required = Objects.requireNonNull(value, message).trim();
        if (required.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return required;
    }
}
