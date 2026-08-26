package com.java.semantic.indexer.job;

import com.java.semantic.model.repository.RepositoryId;
import java.util.Objects;

public final class IndexJobAlreadyActiveException extends RuntimeException {
    private final RepositoryId repositoryId;

    public IndexJobAlreadyActiveException(RepositoryId repositoryId) {
        super("an index job is already active for " + repositoryId.value());
        this.repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
    }

    public RepositoryId repositoryId() {
        return repositoryId;
    }
}
