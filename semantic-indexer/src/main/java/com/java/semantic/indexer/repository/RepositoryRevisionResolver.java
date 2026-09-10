package com.java.semantic.indexer.repository;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Optional;

/** Resolves a repository revision before a job is accepted; no path or credential leaves this boundary. */
public interface RepositoryRevisionResolver {
    RepositoryRevision ensure(RepositoryId repositoryId);
    RepositoryRevision sync(RepositoryId repositoryId, Optional<String> branch);
    RepositoryRevision checkout(RepositoryId repositoryId, String revision);
}
