package com.java.semantic.indexer.review;

import com.java.semantic.model.repository.RepositoryId;
import java.util.Objects;

/** Raised when a review cannot capture an existing published baseline. */
public final class ReviewBaselineUnavailableException extends IllegalStateException {
    private final RepositoryId repositoryId;

    public ReviewBaselineUnavailableException(RepositoryId repositoryId) {
        super("review baseline is unavailable for repository " + Objects.requireNonNull(repositoryId, "repository id is required").value());
        this.repositoryId = repositoryId;
    }

    public RepositoryId repositoryId() {
        return repositoryId;
    }
}
