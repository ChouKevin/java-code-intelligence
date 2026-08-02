package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import java.util.Objects;

/** 固定 revision 的 Java source segment 查詢 */
public record JavaSourceSegmentQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        SourceRange sourceRange,
        int contextLines) {

    public JavaSourceSegmentQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        sourceRange = Objects.requireNonNull(sourceRange, "sourceRange is required");
        if (contextLines < 0 || contextLines > 20) {
            throw new IllegalArgumentException("contextLines must be between 0 and 20");
        }
        if (!sourceRange.sourceFile().endsWith(".java")) {
            throw new IllegalArgumentException("sourceRange must select a Java source file");
        }
    }
}
