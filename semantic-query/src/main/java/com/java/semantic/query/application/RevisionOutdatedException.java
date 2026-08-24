package com.java.semantic.query.application;

import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

public final class RevisionOutdatedException extends RuntimeException {
    private final RepositoryRevision requestedRevision;
    private final RepositoryRevision currentRevision;

    public RevisionOutdatedException(RepositoryRevision requestedRevision, RepositoryRevision currentRevision) {
        super("REVISION_OUTDATED");
        this.requestedRevision = Objects.requireNonNull(requestedRevision, "requested revision is required");
        this.currentRevision = Objects.requireNonNull(currentRevision, "current revision is required");
    }

    public RepositoryRevision requestedRevision() {
        return requestedRevision;
    }

    public RepositoryRevision currentRevision() {
        return currentRevision;
    }
}
