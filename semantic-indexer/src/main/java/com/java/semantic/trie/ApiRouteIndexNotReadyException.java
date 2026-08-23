package com.java.semantic.trie;

import com.java.semantic.diagnostic.ExpectedFailure;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** 指定儲存庫版本的 API 路由索引尚未可用 */
public final class ApiRouteIndexNotReadyException extends RuntimeException implements ExpectedFailure {

    private final RepositoryId repositoryId;
    private final RepositoryRevision expectedRevision;

    public ApiRouteIndexNotReadyException(
            RepositoryId repositoryId,
            RepositoryRevision expectedRevision) {
        super("API route index is not ready for requested repository revision");
        this.repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        this.expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
    }

    public RepositoryId repositoryId() {
        return repositoryId;
    }

    public RepositoryRevision expectedRevision() {
        return expectedRevision;
    }
}
