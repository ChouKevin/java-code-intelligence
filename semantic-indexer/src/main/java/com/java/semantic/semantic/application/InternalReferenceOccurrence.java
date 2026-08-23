package com.java.semantic.semantic.application;

import com.java.semantic.syntax.domain.SyntaxRange;

import java.util.Objects;

/** group context 已擁有 sourceFile 的 repository-local reference 範圍 */
public record InternalReferenceOccurrence(SyntaxRange range) {

    public InternalReferenceOccurrence {
        range = Objects.requireNonNull(range, "range is required");
    }
}
