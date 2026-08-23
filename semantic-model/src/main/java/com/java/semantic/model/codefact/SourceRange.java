package com.java.semantic.model.codefact;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

/** Repository-relative, start-inclusive/end-exclusive UTF-16 source range. */
public record SourceRange(String sourceFile, SyntaxRange range) {

    public SourceRange {
        sourceFile = ModelValidation.repositoryRelativePath(sourceFile);
        range = Objects.requireNonNull(range, "range is required");
    }
}
