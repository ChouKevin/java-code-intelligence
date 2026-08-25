package com.java.semantic.repository.domain;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/** 對 API 安全的儲存庫狀態,不含路徑或認證 */
public record RepositoryStatus(
        RepositoryId repositoryId,
        String displayName,
        String defaultBranch,
        Optional<RepositoryRevision> currentRevision,
        boolean cloned) {

    public RepositoryStatus {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(displayName, "displayName is required");
        Objects.requireNonNull(defaultBranch, "defaultBranch is required");
        Objects.requireNonNull(currentRevision, "currentRevision is required");
    }
}
