package com.java.semantic.model.index;

import com.java.semantic.model.codefact.AnnotationFact;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record SymbolDocument(
        RepositoryId repositoryId,
        GenerationId generationId,
        CodeFact fact,
        CodeFactKind kind,
        String owner,
        String name,
        String signature,
        DeclaredType declaredType,
        Set<String> modifiers,
        List<AnnotationFact> annotations,
        SourceArtifactId sourceArtifactId,
        SourceRange range) {

    public SymbolDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        fact = Objects.requireNonNull(fact, "code fact is required");
        kind = Objects.requireNonNull(kind, "symbol kind is required");
        ModelValidation.require(repositoryId.equals(fact.identity().repositoryId()), "symbol repository must match fact");
        ModelValidation.require(kind == fact.identity().kind(), "symbol kind must match fact");
        ModelValidation.require(kind == CodeFactKind.TYPE
                        || kind == CodeFactKind.METHOD
                        || kind == CodeFactKind.FIELD
                        || kind == CodeFactKind.ENUM_CONSTANT
                        || kind == CodeFactKind.MAPPER_STATEMENT,
                "symbol kind must be a declaration kind");
        owner = Objects.requireNonNull(owner, "owner is required");
        name = Objects.requireNonNull(name, "name is required");
        signature = Objects.requireNonNull(signature, "signature is required");
        declaredType = Objects.requireNonNull(declaredType, "declared type is required");
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers, "modifiers are required"));
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        sourceArtifactId = Objects.requireNonNull(sourceArtifactId, "source artifact id is required");
        range = Objects.requireNonNull(range, "source range is required");
    }
}
