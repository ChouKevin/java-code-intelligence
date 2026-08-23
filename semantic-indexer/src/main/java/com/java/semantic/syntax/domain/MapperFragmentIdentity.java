package com.java.semantic.syntax.domain;

import java.util.Objects;

/** mapper `<sql>` fragment 在單一 repository syntax 快照內的型別化識別 */
public record MapperFragmentIdentity(
        String namespace,
        String fragmentId,
        String resourcePath,
        int documentOrdinal,
        MapperEvidenceRepresentation representation) {

    public MapperFragmentIdentity {
        namespace = requiredText(namespace, "namespace");
        fragmentId = requiredText(fragmentId, "fragmentId");
        resourcePath = requiredText(resourcePath, "resourcePath");
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
