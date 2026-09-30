package com.java.semantic.model.index.persistence;

import com.java.semantic.model.codefact.AnnotationFact;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Optional-free persisted symbol shape; an empty operation means a non-mapper declaration. */
public record SymbolPersistence(
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
        SourceRange range,
        String mapperStatementKind) {

    public SymbolPersistence {
        mapperStatementKind = Objects.requireNonNull(mapperStatementKind, "persisted mapper statement kind is required");
    }

    public static SymbolPersistence from(SymbolDocument symbol) {
        Objects.requireNonNull(symbol, "symbol is required");
        return new SymbolPersistence(symbol.repositoryId(), symbol.generationId(), symbol.fact(), symbol.kind(),
                symbol.owner(), symbol.name(), symbol.signature(), symbol.declaredType(), symbol.modifiers(),
                symbol.annotations(), symbol.sourceArtifactId(), symbol.range(),
                symbol.mapperStatementKind().map(MapperStatementKind::name).orElse(""));
    }

    public SymbolDocument toModel() {
        Optional<MapperStatementKind> statementKind = mapperStatementKind.isEmpty()
                ? Optional.empty() : Optional.of(MapperStatementKind.valueOf(mapperStatementKind));
        return new SymbolDocument(repositoryId, generationId, fact, kind, owner, name, signature, declaredType,
                modifiers, annotations, sourceArtifactId, range, statementKind);
    }
}
