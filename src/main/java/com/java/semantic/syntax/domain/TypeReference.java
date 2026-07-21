package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;

import org.springframework.util.Assert;

/** 原始碼寫法與 binding 解析結果的不可變型別證據 */
public record TypeReference(
        String writtenType,
        String resolvedType,
        List<TypeReference> typeArguments,
        List<TypeReference> upperBounds,
        List<TypeReference> lowerBounds,
        boolean sourceDefined) {

    public TypeReference {
        Assert.hasText(writtenType, "writtenType is required");
        resolvedType = Objects.requireNonNullElse(resolvedType, "");
        typeArguments = List.copyOf(Objects.requireNonNull(typeArguments, "typeArguments is required"));
        upperBounds = List.copyOf(Objects.requireNonNull(upperBounds, "upperBounds is required"));
        lowerBounds = List.copyOf(Objects.requireNonNull(lowerBounds, "lowerBounds is required"));
    }

    public TypeReference(
            String writtenType,
            String resolvedType,
            List<TypeReference> typeArguments,
            boolean sourceDefined) {
        this(writtenType, resolvedType, typeArguments, List.of(), List.of(), sourceDefined);
    }
}
