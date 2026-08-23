package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.JavaTypeIdentity;

/** 註解的原始寫法與可選的 JDT 已解析型別識別。 */
public record AnnotationEvidence(String writtenName, Optional<JavaTypeIdentity> resolvedType) {

    public AnnotationEvidence {
        require(hasText(writtenName), "writtenName is required");
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
    }

    private static boolean hasText(String value) {
        return !Objects.requireNonNullElse(value, "").isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
