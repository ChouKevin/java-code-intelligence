package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.java.semantic.identity.MethodTarget;

/** 一個 mapper statement 變體的未求值原始證據 */
public record MapperStatementEvidence(
        MapperStatementIdentity identity,
        String statementKind,
        String content,
        List<String> includeRefIds,
        Optional<MethodTarget> mappedMethodTarget) {

    public MapperStatementEvidence {
        identity = Objects.requireNonNull(identity, "identity is required");
        statementKind = requiredText(statementKind, "statementKind");
        content = Objects.requireNonNull(content, "content is required");
        includeRefIds = List.copyOf(Objects.requireNonNull(includeRefIds, "includeRefIds are required"));
        mappedMethodTarget = Objects.requireNonNull(mappedMethodTarget, "mappedMethodTarget is required");
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
