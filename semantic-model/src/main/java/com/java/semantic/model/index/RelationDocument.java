package com.java.semantic.model.index;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.Set;

public record RelationDocument(
        RepositoryId repositoryId,
        GenerationId generationId,
        CodeFact fact,
        RelationKind kind,
        CodeFactIdentity from,
        RelationTarget target,
        SourceArtifactId sourceArtifactId,
        SourceRange range) {

    private static final Set<CodeFactKind> RELATION_FACT_KINDS = Set.of(
            CodeFactKind.ANNOTATION_USAGE,
            CodeFactKind.TYPE_USAGE,
            CodeFactKind.SQL_IDENTIFIER,
            CodeFactKind.CONFIGURATION_KEY,
            CodeFactKind.OUTBOUND_API,
            CodeFactKind.MQ_PUBLISHER,
            CodeFactKind.ERROR_CONTRACT);

    public RelationDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        fact = Objects.requireNonNull(fact, "code fact is required");
        kind = Objects.requireNonNull(kind, "relation kind is required");
        from = Objects.requireNonNull(from, "from identity is required");
        target = Objects.requireNonNull(target, "relation target is required");
        sourceArtifactId = Objects.requireNonNull(sourceArtifactId, "source artifact id is required");
        range = Objects.requireNonNull(range, "source range is required");
        ModelValidation.require(repositoryId.equals(fact.identity().repositoryId())
                        && repositoryId.equals(from.repositoryId()), "relation repository must match fact and source");
        ModelValidation.require(fact.identity().repositoryRevision().equals(from.repositoryRevision()),
                "relation revision must match source");
        ModelValidation.require(RELATION_FACT_KINDS.contains(fact.identity().kind()),
                "relation fact kind must be a relation or usage kind");
        ModelValidation.require(fact.identity().canonicalIdentity().equals(new RelationIdentity(from, kind, target, range)),
                "relation fact identity must describe the relation document");
        if (target instanceof RelationTarget.Internal internalTarget) {
            CodeFactIdentity internalIdentity = internalTarget.identity();
            ModelValidation.require(repositoryId.equals(internalIdentity.repositoryId()),
                    "relation target repository must match");
            ModelValidation.require(fact.identity().repositoryRevision().equals(internalIdentity.repositoryRevision()),
                    "relation target revision must match");
        }
    }
}
