package com.java.semantic.api.dto;

import java.util.Optional;

/** 不含路徑、URL、認證與 JGit 型別的儲存庫狀態 */
public record RepositoryStatusResponse(
        String repoId,
        String mode,
        String displayName,
        Optional<String> currentBranch,
        Optional<String> currentRevision,
        boolean cloned) {
}
