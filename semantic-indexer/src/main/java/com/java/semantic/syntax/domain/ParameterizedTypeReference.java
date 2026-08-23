package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;

/** 含有依原始碼順序型別引數的名義型別證據 */
public record ParameterizedTypeReference(
        String writtenType,
        NamedTypeReference rawType,
        List<TypeReference> typeArguments) implements NominalTypeReference {

    public ParameterizedTypeReference {
        writtenType = Objects.requireNonNull(writtenType, "writtenType is required").trim();
        if (writtenType.isBlank()) {
            throw new IllegalArgumentException("writtenType is required");
        }
        rawType = Objects.requireNonNull(rawType, "rawType is required");
        typeArguments = List.copyOf(Objects.requireNonNull(typeArguments, "typeArguments is required"));
    }

    @Override
    public String simpleTypeName() {
        return rawType.simpleTypeName();
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return rawType.resolvedNamedType();
    }

    @Override
    public boolean sourceDefined() {
        return rawType.sourceDefined();
    }
}
