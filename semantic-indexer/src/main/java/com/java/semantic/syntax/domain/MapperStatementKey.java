package com.java.semantic.syntax.domain;

import java.util.Objects;

/** mapper statement 的 logical namespace 與 statement ID 識別 */
public record MapperStatementKey(String namespace, String statementId) {

    public MapperStatementKey {
        namespace = requiredText(namespace, "namespace");
        statementId = requiredText(statementId, "statementId");
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
