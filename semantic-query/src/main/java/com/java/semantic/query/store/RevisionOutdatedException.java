package com.java.semantic.query.store;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** A query pinned to an old repository revision must refresh before reading projections. */
public final class RevisionOutdatedException extends RuntimeException {

    private final RepositoryRevision expectedRevision;
    private final CurrentGeneration currentGeneration;

    public RevisionOutdatedException(RepositoryRevision expectedRevision, CurrentGeneration currentGeneration) {
        super("REVISION_OUTDATED");
        this.expectedRevision = Objects.requireNonNull(expectedRevision, "expected revision is required");
        this.currentGeneration = Objects.requireNonNull(currentGeneration, "current generation is required");
    }

    public RepositoryRevision expectedRevision() {
        return expectedRevision;
    }

    public CurrentGeneration currentGeneration() {
        return currentGeneration;
    }
}
