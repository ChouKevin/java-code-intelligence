package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;

import java.util.Objects;

/** Store-level exact compare-and-set input for swapping the bounded rollback pointer. */
public record RollbackGenerationCommand(
        RepositoryId repositoryId,
        PublishedGenerationPointer expectedCurrent,
        PublishedGenerationPointer expectedRollback,
        String jobId) {

    public RollbackGenerationCommand {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        expectedCurrent = Objects.requireNonNull(expectedCurrent, "expected current pointer is required");
        expectedRollback = Objects.requireNonNull(expectedRollback, "expected rollback pointer is required");
        jobId = com.java.semantic.model.support.ModelValidation.requiredText(jobId, "job id");
    }
}
