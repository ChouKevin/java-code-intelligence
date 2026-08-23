package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.model.codefact.JavaTypeIdentity;

/** 聯集或交集型別的依原始碼順序替代證據 */
public record CompositeTypeReference(
        String writtenType,
        CompositeKind kind,
        List<TypeReference> alternatives,
        boolean sourceDefined) implements TypeReference {

    public CompositeTypeReference {
        writtenType = Objects.requireNonNull(writtenType, "writtenType is required").trim();
        if (writtenType.isBlank()) {
            throw new IllegalArgumentException("writtenType is required");
        }
        kind = Objects.requireNonNull(kind, "kind is required");
        alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives is required"));
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("alternatives is required");
        }
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return Optional.empty();
    }

    /** 複合型別的語法關係 */
    public enum CompositeKind {
        UNION,
        INTERSECTION
    }
}
