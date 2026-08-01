package com.java.semantic.syntax.application;

import com.java.semantic.identity.RepositoryRelativeSource;
import com.java.semantic.syntax.domain.SyntaxRange;

import java.util.Objects;

/** revision-bound 原始碼證據，使用零基 UTF-16 且 start-inclusive/end-exclusive */
public record SourceRange(String sourceFile, SyntaxRange range) {

    public SourceRange {
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        range = Objects.requireNonNull(range, "range is required");
    }
}
