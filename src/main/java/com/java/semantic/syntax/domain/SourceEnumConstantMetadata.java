package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;

/** enum 常數的來源宣告與 annotation 證據 */
public record SourceEnumConstantMetadata(
        String name,
        SourceRange declarationLocation,
        List<AnnotationEvidence> annotationEvidence) {

    public SourceEnumConstantMetadata {
        name = Objects.requireNonNull(name, "name is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        annotationEvidence = List.copyOf(Objects.requireNonNull(annotationEvidence, "annotationEvidence is required"));
    }
}
