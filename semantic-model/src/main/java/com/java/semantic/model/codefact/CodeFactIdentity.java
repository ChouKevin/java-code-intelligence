package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

public record CodeFactIdentity(
        RepositoryId repositoryId,
        RepositoryRevision repositoryRevision,
        CodeFactKind kind,
        CanonicalIdentity canonicalIdentity) {

    public CodeFactIdentity {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        repositoryRevision = Objects.requireNonNull(repositoryRevision, "repository revision is required");
        kind = Objects.requireNonNull(kind, "code fact kind is required");
        canonicalIdentity = Objects.requireNonNull(canonicalIdentity, "canonical identity is required");
    }

    public String canonicalForm() {
        return repositoryId.value() + "|" + repositoryRevision.value() + "|" + kind.name() + "|"
                + canonicalIdentity.canonicalForm();
    }
}
