package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.SourceRange;

import java.util.Objects;

/** 固定 revision 的 canonical source segment 查詢 */
public record SourceSegmentQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        SourceRange location,
        int contextLines) {

    public SourceSegmentQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        location = Objects.requireNonNull(location, "location is required");
        if (contextLines < 0 || contextLines > 20) {
            throw new IllegalArgumentException("contextLines must be between 0 and 20");
        }
    }
}
