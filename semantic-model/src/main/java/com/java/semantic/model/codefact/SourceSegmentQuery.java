package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** Caller-supplied stored source range, authorized through its declaring source type. */
public record SourceSegmentQuery(RepositoryId repositoryId, RepositoryRevision revision, SourceTypeIdentity sourceType,
                                 SourceRange range) {
    public SourceSegmentQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        sourceType = Objects.requireNonNull(sourceType, "source type is required");
        range = Objects.requireNonNull(range, "range is required");
        if (!sourceType.sourceFile().equals(range.sourceFile())) { throw new IllegalArgumentException("range must belong to source type path"); }
    }
}
