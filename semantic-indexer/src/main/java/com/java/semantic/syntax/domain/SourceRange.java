package com.java.semantic.syntax.domain;

import com.java.semantic.identity.RepositoryRelativeSource;

import java.util.Objects;

/** revision-bound 原始碼位置，使用零基 UTF-16 且 start-inclusive/end-exclusive */
public record SourceRange(String sourceFile, SyntaxRange range) {

    public SourceRange {
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        range = Objects.requireNonNull(range, "range is required");
    }
}
