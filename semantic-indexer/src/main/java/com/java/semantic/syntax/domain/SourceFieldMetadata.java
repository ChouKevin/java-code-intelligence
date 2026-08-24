package com.java.semantic.syntax.domain;

import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.SourceRange;
import java.util.List;
import java.util.Objects;

/** 來源型別欄位的語法與 annotation 證據 */
public record SourceFieldMetadata(
        String name,
        String type,
        String qualifier,
        TypeReference typeReference,
        List<AnnotationEvidence> annotationEvidence,
        CodeFactKind declarationKind,
        SourceRange declarationLocation) {

    public SourceFieldMetadata {
        qualifier = Objects.requireNonNullElse(qualifier, "");
        typeReference = Objects.requireNonNull(typeReference, "typeReference is required");
        annotationEvidence = List.copyOf(annotationEvidence);
        declarationKind = Objects.requireNonNull(declarationKind, "declarationKind is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
    }
}
