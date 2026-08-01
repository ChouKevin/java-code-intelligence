package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;

/** 上界或下界擇一的萬用字元型別證據 */
public record WildcardTypeReference(
        String writtenType,
        Optional<TypeReference> upperBound,
        Optional<TypeReference> lowerBound,
        boolean sourceDefined) implements TypeReference {

    public WildcardTypeReference {
        writtenType = Objects.requireNonNull(writtenType, "writtenType is required").trim();
        if (writtenType.isBlank()) {
            throw new IllegalArgumentException("writtenType is required");
        }
        upperBound = Objects.requireNonNull(upperBound, "upperBound is required");
        lowerBound = Objects.requireNonNull(lowerBound, "lowerBound is required");
        if (upperBound.isPresent() && lowerBound.isPresent()) {
            throw new IllegalArgumentException("a wildcard cannot have both upper and lower bounds");
        }
    }

    @Override
    public Optional<JavaTypeIdentity> resolvedNamedType() {
        return Optional.empty();
    }
}
