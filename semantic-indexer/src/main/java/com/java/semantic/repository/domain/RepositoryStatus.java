package com.java.semantic.repository.domain;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;

/** 對 API 安全的儲存庫狀態,不含路徑、網址或認證 */
public record RepositoryStatus(
        RepositoryId repositoryId,
        RepositoryMode mode,
        String displayName,
        Optional<String> currentBranch,
        Optional<RepositoryRevision> currentRevision,
        boolean cloned) {

    public RepositoryStatus {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(displayName, "displayName is required");
        Objects.requireNonNull(currentBranch, "currentBranch is required");
        Objects.requireNonNull(currentRevision, "currentRevision is required");
    }
}
