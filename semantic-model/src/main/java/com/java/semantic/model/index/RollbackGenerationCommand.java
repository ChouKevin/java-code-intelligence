package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;

import java.util.Objects;

/** Store-level exact compare-and-set input for swapping the bounded rollback pointer. */
public record RollbackGenerationCommand(
        RepositoryId repositoryId,
        PublishedGenerationPointer expectedCurrent,
        PublishedGenerationPointer expectedRollback,
        String activeJobId,
        String activeWorkerId,
        RepositoryFence activeFence) {

    public RollbackGenerationCommand {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        expectedCurrent = Objects.requireNonNull(expectedCurrent, "expected current pointer is required");
        expectedRollback = Objects.requireNonNull(expectedRollback, "expected rollback pointer is required");
        activeJobId = com.java.semantic.model.support.ModelValidation.requiredText(activeJobId, "active job id");
        activeWorkerId = com.java.semantic.model.support.ModelValidation.requiredText(activeWorkerId, "active worker id");
        activeFence = Objects.requireNonNull(activeFence, "active fence is required");
    }
}
