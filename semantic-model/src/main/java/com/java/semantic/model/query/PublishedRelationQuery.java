package com.java.semantic.model.query;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** Revision-pinned request for relations whose internal target is one canonical fact. */
public record PublishedRelationQuery(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        CodeFactIdentity target,
        int offset,
        int limit) {

    public PublishedRelationQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        target = Objects.requireNonNull(target, "target is required");
        if (!repositoryId.equals(target.repositoryId()) || !revision.equals(target.repositoryRevision())) {
            throw new IllegalArgumentException("relation target repository and revision must match the request");
        }
        if (offset < 0 || limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("relation page is invalid");
        }
    }
}
