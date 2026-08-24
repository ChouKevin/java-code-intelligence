package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.Optional;

/** Declaration-only source-symbol query; it never resolves arbitrary source usages. */
public record DeclarationResolutionQuery(
        RepositoryId repositoryId,
        RepositoryRevision revision,
        SourceTypeIdentity context,
        String symbol,
        Optional<SyntaxPosition> position) {

    public DeclarationResolutionQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        context = Objects.requireNonNull(context, "context is required");
        symbol = ModelValidation.requiredText(symbol, "symbol");
        position = Objects.requireNonNull(position, "position is required");
    }
}
