package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;

/** 來源型別欄位的語法與 annotation 證據 */
public record SourceFieldMetadata(
        String name,
        String type,
        String qualifier,
        TypeReference typeReference,
        List<AnnotationEvidence> annotationEvidence) {

    public SourceFieldMetadata {
        qualifier = Objects.requireNonNullElse(qualifier, "");
        typeReference = Objects.requireNonNull(typeReference, "typeReference is required");
        annotationEvidence = List.copyOf(annotationEvidence);
    }
}
