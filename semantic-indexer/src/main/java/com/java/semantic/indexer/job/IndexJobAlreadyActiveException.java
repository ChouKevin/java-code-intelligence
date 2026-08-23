package com.java.semantic.indexer.job;

import com.java.semantic.model.repository.RepositoryId;

public final class IndexJobAlreadyActiveException extends RuntimeException {
    public IndexJobAlreadyActiveException(RepositoryId repositoryId) {
        super("an index job is already active for " + repositoryId.value());
    }
}
