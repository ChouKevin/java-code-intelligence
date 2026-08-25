package com.java.semantic.model.query;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** Revision-pinned bounded traversal request over persisted CALLS relations. */
public record PublishedCallGraphQuery(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        MethodTarget root,
        int depth,
        int depthTwoNodeBudget) {

    public PublishedCallGraphQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        root = Objects.requireNonNull(root, "root is required");
        if (depth < 1 || depth > 2 || depthTwoNodeBudget < 0) {
            throw new IllegalArgumentException("call graph bounds are invalid");
        }
    }
}
