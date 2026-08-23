package com.java.semantic.syntax.adapter.cache;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import java.util.Objects;

/** Caffeine adapter 內部使用的精確 repository revision 快取身分 */
record RepositorySyntaxCacheKey(
        RepositoryId repositoryId,
        RepositoryRevision revision) {

    RepositorySyntaxCacheKey {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        revision = Objects.requireNonNull(revision, "revision is required");
    }
}
