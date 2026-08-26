package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;

import java.util.Objects;
import java.util.Optional;

/** Current and rollback generation pointers for one repository. */
public record RepositoryIndexDocument(
        RepositoryId repositoryId,
        Optional<PublishedGenerationPointer> currentPointer,
        Optional<PublishedGenerationPointer> rollbackPointer) {

    public RepositoryIndexDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        currentPointer = Objects.requireNonNull(currentPointer, "current pointer is required");
        rollbackPointer = Objects.requireNonNull(rollbackPointer, "rollback pointer is required");
    }
}
