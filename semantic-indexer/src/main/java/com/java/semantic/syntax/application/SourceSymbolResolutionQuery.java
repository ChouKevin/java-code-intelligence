package com.java.semantic.syntax.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.codefact.SyntaxPosition;

import java.util.Objects;
import java.util.Optional;

/** fixed revision source symbol resolution query */
public record SourceSymbolResolutionQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        SourceSymbolContext context,
        String symbol,
        Optional<SyntaxPosition> position) {

    public SourceSymbolResolutionQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        context = Objects.requireNonNull(context, "context is required");
        symbol = requiredIdentifier(symbol);
        position = Objects.requireNonNull(position, "position is required");
    }

    private static String requiredIdentifier(String value) {
        String identifier = Objects.requireNonNull(value, "symbol is required");
        if (identifier.isBlank()
                || !Character.isJavaIdentifierStart(identifier.codePointAt(0))
                || !identifier.codePoints().skip(1).allMatch(Character::isJavaIdentifierPart)) {
            throw new IllegalArgumentException("symbol must be one Java identifier");
        }
        return identifier;
    }
}
