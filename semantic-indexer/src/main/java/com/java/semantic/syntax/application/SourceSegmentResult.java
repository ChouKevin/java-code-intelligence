package com.java.semantic.syntax.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.codefact.SourceRange;

import java.util.Objects;
import java.util.Optional;

/** 固定 revision 內已受 byte bound 限制的 canonical source segment */
public record SourceSegmentResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        SourceRange location,
        String content,
        Optional<SourceRange> nextLocation,
        boolean contextTruncated) {

    public SourceSegmentResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        location = Objects.requireNonNull(location, "location is required");
        content = Objects.requireNonNull(content, "content is required");
        nextLocation = Objects.requireNonNull(nextLocation, "nextLocation is required");
    }
}
