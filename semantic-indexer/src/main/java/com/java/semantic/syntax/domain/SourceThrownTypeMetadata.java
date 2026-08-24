package com.java.semantic.syntax.domain;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.SourceRange;
import java.util.Objects;
import java.util.Optional;

/** One syntactic type occurrence in a method throws clause. */
public record SourceThrownTypeMetadata(String writtenName, Optional<JavaTypeIdentity> resolvedType,
                                       SourceRange occurrence) {

    public SourceThrownTypeMetadata {
        writtenName = Objects.requireNonNull(writtenName, "written thrown type is required");
        resolvedType = Objects.requireNonNull(resolvedType, "resolved thrown type is required");
        occurrence = Objects.requireNonNull(occurrence, "thrown type occurrence is required");
    }
}
