package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Optional;

/** Resolves the source revision before a job is accepted; no path or credential leaves this boundary. */
public interface RepositorySourcePort {
    RepositoryRevision ensure(RepositoryId repositoryId);
    RepositoryRevision sync(RepositoryId repositoryId, Optional<String> branch);
    RepositoryRevision checkout(RepositoryId repositoryId, String revision);
}
