package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SyntaxRange;

/** 註解的原始寫法與可選的 JDT 已解析型別識別。 */
public record AnnotationEvidence(
        String writtenName,
        Optional<JavaTypeIdentity> resolvedType,
        Optional<SyntaxRange> range,
        List<AnnotationValueEvidence> values) {

    public AnnotationEvidence {
        require(hasText(writtenName), "writtenName is required");
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
        range = Objects.requireNonNull(range, "range is required");
        values = List.copyOf(Objects.requireNonNull(values, "values are required"));
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
