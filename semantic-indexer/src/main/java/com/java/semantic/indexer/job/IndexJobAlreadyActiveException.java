package com.java.semantic.indexer.job;

import com.java.semantic.model.repository.RepositoryId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "REPOSITORY_ACTIVE")
public final class IndexJobAlreadyActiveException extends RuntimeException {
    public IndexJobAlreadyActiveException(RepositoryId repositoryId) {
        super("an index job is already active for " + repositoryId.value());
    }
}
