package com.java.semantic.syntax.domain;

import java.util.Objects;
import java.util.Optional;

/** mapper statement 變體在單一 repository syntax 快照內的型別化識別 */
public record MapperStatementIdentity(
        String namespace,
        String statementId,
        String resourcePath,
        Optional<String> databaseId,
        int documentOrdinal,
        MapperEvidenceRepresentation representation) {

    public MapperStatementIdentity {
        namespace = requiredText(namespace, "namespace");
        statementId = requiredText(statementId, "statementId");
        resourcePath = requiredText(resourcePath, "resourcePath");
        databaseId = Objects.requireNonNull(databaseId, "databaseId is required")
                .map(value -> requiredText(value, "databaseId"));
        if (documentOrdinal < 0) {
            throw new IllegalArgumentException("documentOrdinal must not be negative");
        }
        representation = Objects.requireNonNull(representation, "representation is required");
    }

    private static String requiredText(String value, String fieldName) {
        String text = Objects.requireNonNull(value, fieldName + " is required");
        if (text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return text;
    }
}
