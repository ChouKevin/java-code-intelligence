package com.java.semantic.syntax.application.concept;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** 固定 revision 內精確 typed concept identity resolve 的查詢 */
public record ConceptResolveQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        ConceptIdentity identity) {

    public ConceptResolveQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        identity = Objects.requireNonNull(identity, "identity is required");
    }
}
