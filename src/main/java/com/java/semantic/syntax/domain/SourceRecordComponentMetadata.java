package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;

/** record component 的來源型別、宣告與 annotation 證據 */
public record SourceRecordComponentMetadata(
        String name,
        String type,
        String qualifier,
        TypeReference typeReference,
        SourceRange declarationLocation,
        List<AnnotationEvidence> annotationEvidence) {

    public SourceRecordComponentMetadata {
        name = Objects.requireNonNull(name, "name is required");
        type = Objects.requireNonNull(type, "type is required");
        qualifier = Objects.requireNonNullElse(qualifier, "");
        typeReference = Objects.requireNonNull(typeReference, "typeReference is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        annotationEvidence = List.copyOf(Objects.requireNonNull(annotationEvidence, "annotationEvidence is required"));
    }
}
