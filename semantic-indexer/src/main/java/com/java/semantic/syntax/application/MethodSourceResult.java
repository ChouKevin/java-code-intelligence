package com.java.semantic.syntax.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceRangeSegment;

import java.util.Objects;

/** 已解析 canonical 宣告位置與首個 bounded 原始碼區段 */
public record MethodSourceResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        SourceRange declarationLocation,
        SourceRangeSegment segment,
        boolean implementationDiscoveryEligible) {

    public MethodSourceResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        declarationLocation = Objects.requireNonNull(declarationLocation, "declarationLocation is required");
        segment = Objects.requireNonNull(segment, "segment is required");
    }
}
