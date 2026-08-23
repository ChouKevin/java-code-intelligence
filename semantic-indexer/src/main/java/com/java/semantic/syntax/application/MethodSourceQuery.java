package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import java.util.Objects;

/** 以完整 canonical 方法目標讀取固定 revision 的宣告原始碼 */
public record MethodSourceQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        MethodTarget target) {

    public MethodSourceQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        target = Objects.requireNonNull(target, "target is required");
    }
}
