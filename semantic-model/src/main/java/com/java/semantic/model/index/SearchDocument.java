package com.java.semantic.model.index;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Derived search projection; its authority is always one of the primary fact collections. */
public record SearchDocument(
        RepositoryId repositoryId,
        GenerationId generationId,
        CodeFactId factId,
        CodeFactKind kind,
        List<String> normalizedTokens,
        Optional<String> packageName,
        ProjectionName authoritativeProjection,
        CodeFactIdentity authoritativeIdentity,
        CodeFactScope scope) {

    public SearchDocument(RepositoryId repositoryId, GenerationId generationId, CodeFactId factId, CodeFactKind kind,
                          List<String> normalizedTokens, Optional<String> packageName,
                          ProjectionName authoritativeProjection, CodeFactIdentity authoritativeIdentity) {
        this(repositoryId, generationId, factId, kind, normalizedTokens, packageName, authoritativeProjection,
                authoritativeIdentity, CodeFactScope.from(authoritativeIdentity));
    }

    public SearchDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        factId = Objects.requireNonNull(factId, "code fact id is required");
        kind = Objects.requireNonNull(kind, "code fact kind is required");
        normalizedTokens = List.copyOf(Objects.requireNonNull(normalizedTokens, "normalized tokens are required"));
        packageName = Objects.requireNonNull(packageName, "package name is required");
        authoritativeProjection = Objects.requireNonNull(authoritativeProjection, "authoritative projection is required");
        authoritativeIdentity = Objects.requireNonNull(authoritativeIdentity, "authoritative identity is required");
        scope = Objects.requireNonNull(scope, "authority scope is required");
        ModelValidation.require(repositoryId.equals(authoritativeIdentity.repositoryId()), "search repository must match authority");
        ModelValidation.require(kind == authoritativeIdentity.kind(), "search kind must match authority");
        ModelValidation.require(!normalizedTokens.isEmpty(), "normalized tokens must not be empty");
        ModelValidation.require(authoritativeProjection == ProjectionName.SYMBOLS
                        || authoritativeProjection == ProjectionName.RELATIONS
                        || authoritativeProjection == ProjectionName.ENTRY_POINTS,
                "search authority must be symbols, relations, or entry points");
        for (String token : normalizedTokens) {
            String value = ModelValidation.requiredText(token, "normalized token");
            ModelValidation.require(value.equals(value.toLowerCase(Locale.ROOT)) && value.matches("[a-z0-9_.-]+"),
                    "search token must be normalized and code-derived");
        }
        ModelValidation.require(factId.equals(com.java.semantic.model.codefact.CodeFactId.from(authoritativeIdentity)),
                "search fact id must match authoritative identity");
        ModelValidation.require(authoritativeProjection == expectedProjection(kind),
                "search authority must match code fact kind");
        ModelValidation.require(scope.equals(CodeFactScope.from(authoritativeIdentity)),
                "search authority scope must match authoritative identity");
    }

    private static ProjectionName expectedProjection(CodeFactKind kind) {
        return switch (kind) {
            case API_ROUTE, MQ_DESTINATION, SCHEDULE -> ProjectionName.ENTRY_POINTS;
            case TYPE_USAGE, SQL_IDENTIFIER, CONFIGURATION_KEY, OUTBOUND_API, MQ_PUBLISHER, ERROR_CONTRACT,
                    ANNOTATION_USAGE -> ProjectionName.RELATIONS;
            default -> ProjectionName.SYMBOLS;
        };
    }
}
