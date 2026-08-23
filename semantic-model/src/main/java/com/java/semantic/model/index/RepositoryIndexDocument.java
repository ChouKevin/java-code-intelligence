package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Latest-only repository coordinator and current-generation pointer. */
public record RepositoryIndexDocument(
        RepositoryId repositoryId,
        RepositoryFence fence,
        Optional<String> activeJobId,
        Optional<String> activeWorkerId,
        Optional<GenerationId> activeGenerationId,
        Optional<Instant> claimExpiresAt,
        Optional<PublishedGenerationPointer> currentPointer,
        Optional<PublishedGenerationPointer> rollbackPointer) {

    public RepositoryIndexDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        fence = Objects.requireNonNull(fence, "repository fence is required");
        activeJobId = Objects.requireNonNull(activeJobId, "active job id is required");
        activeWorkerId = Objects.requireNonNull(activeWorkerId, "active worker id is required");
        activeGenerationId = Objects.requireNonNull(activeGenerationId, "active generation id is required");
        claimExpiresAt = Objects.requireNonNull(claimExpiresAt, "claim expiry is required");
        currentPointer = Objects.requireNonNull(currentPointer, "current pointer is required");
        rollbackPointer = Objects.requireNonNull(rollbackPointer, "rollback pointer is required");
    }
}
