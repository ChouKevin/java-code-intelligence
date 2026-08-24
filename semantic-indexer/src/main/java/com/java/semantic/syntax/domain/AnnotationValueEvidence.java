package com.java.semantic.syntax.domain;

import com.java.semantic.model.codefact.SyntaxRange;
import java.util.Objects;
import java.util.Optional;

/** An AST-backed annotation member value, independent of the parser implementation. */
public record AnnotationValueEvidence(
        String memberName,
        String writtenValue,
        Optional<String> literalValue,
        SyntaxRange range) {

    public AnnotationValueEvidence {
        memberName = requiredText(memberName, "memberName is required");
        writtenValue = requiredText(writtenValue, "writtenValue is required");
        literalValue = Objects.requireNonNull(literalValue, "literalValue is required");
        range = Objects.requireNonNull(range, "range is required");
    }

    private static String requiredText(String value, String message) {
        String required = Objects.requireNonNullElse(value, "");
        if (required.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return required;
    }
}
