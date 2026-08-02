package com.java.semantic.semantic.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;

import java.util.Objects;

/** 固定 revision 與 exact target 的內部 reference group 分頁查詢 */
public record InternalSourceReferenceQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        ExactSourceDeclarationTarget target,
        int offset,
        int limit) {

    public InternalSourceReferenceQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        target = Objects.requireNonNull(target, "target is required");
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("internal reference page is invalid");
        }
    }
}
