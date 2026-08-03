package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SourceRangeSegment;

import java.util.Objects;

/** 已由固定 snapshot 回讀的單一 typed evidence source */
public record EvidenceSourceResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        EvidenceSourceQuery.EvidenceIdentity identity,
        SourceRange location,
        SourceRangeSegment segment) {

    public EvidenceSourceResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        identity = Objects.requireNonNull(identity, "identity is required");
        location = Objects.requireNonNull(location, "location is required");
        segment = Objects.requireNonNull(segment, "segment is required");
    }
}
