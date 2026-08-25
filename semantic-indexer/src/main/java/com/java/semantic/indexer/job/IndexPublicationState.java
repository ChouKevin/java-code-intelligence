package com.java.semantic.indexer.job;

import com.java.semantic.model.index.PublishedGenerationPointer;

import java.util.Objects;
import java.util.Optional;

/** Current and bounded rollback pointers read from one repository coordinator document. */
public record IndexPublicationState(
        Optional<PublishedGenerationPointer> currentPointer,
        Optional<PublishedGenerationPointer> rollbackPointer) {

    public IndexPublicationState {
        currentPointer = Objects.requireNonNull(currentPointer, "current pointer is required");
        rollbackPointer = Objects.requireNonNull(rollbackPointer, "rollback pointer is required");
    }
}
