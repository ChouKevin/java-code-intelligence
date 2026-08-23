package com.java.semantic.repository.domain;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.nio.file.Path;
import java.util.Objects;

/** 在讀鎖生命週期內可安全使用的儲存庫快照 */
public record RepositorySnapshot(
        RepositoryId repositoryId,
        Path root,
        RepositoryRevision revision) {

    public RepositorySnapshot {
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        Objects.requireNonNull(root, "root is required");
        Objects.requireNonNull(revision, "revision is required");
    }
}
