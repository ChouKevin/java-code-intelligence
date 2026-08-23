package com.java.semantic.semantic.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import java.util.Objects;

/** 固定 revision 的方法實作探索請求，不提供分頁或續傳游標 */
public record MethodImplementationDiscoveryQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        MethodTarget declarationTarget) {

    public MethodImplementationDiscoveryQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        declarationTarget = Objects.requireNonNull(declarationTarget, "declarationTarget is required");
    }
}
