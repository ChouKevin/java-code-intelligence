package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** Revision-pinned exact code-fact request using only a previously published opaque fact identifier. */
public record CodeFactReadQuery(RepositoryId repositoryId, RepositoryRevision revision, CodeFactId factId) {

    public CodeFactReadQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "repository revision is required");
        factId = Objects.requireNonNull(factId, "code fact id is required");
    }
}
