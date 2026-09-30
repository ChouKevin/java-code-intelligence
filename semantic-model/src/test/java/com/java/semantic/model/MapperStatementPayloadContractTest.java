package com.java.semantic.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.persistence.SymbolPersistence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapperStatementPayloadContractTest {
    private static final RepositoryId REPOSITORY = new RepositoryId("orders");
    private static final RepositoryRevision REVISION = new RepositoryRevision("a".repeat(40));
    private static final GenerationId GENERATION = new GenerationId("g1");
    private static final SourceRange RANGE = new SourceRange("src/Mapper.java",
            new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)));

    @Test
    void mapper_and_declaration_payloads_cannot_be_missing_or_attached_to_the_wrong_fact_kind() {
        assertThrows(IllegalArgumentException.class, () -> symbol(CodeFactKind.MAPPER_STATEMENT, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> symbol(CodeFactKind.METHOD, Optional.of(MapperStatementKind.SELECT)));
        SymbolDocument mapper = symbol(CodeFactKind.MAPPER_STATEMENT, Optional.of(MapperStatementKind.UPDATE));
        SymbolDocument method = symbol(CodeFactKind.METHOD, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> details(mapper, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> details(method, Optional.of(MapperStatementKind.UPDATE)));
    }

    @Test
    void persisted_missing_unknown_or_wrong_kind_operations_are_not_legacy_decoded() {
        SymbolDocument mapper = symbol(CodeFactKind.MAPPER_STATEMENT, Optional.of(MapperStatementKind.UPDATE));
        assertThrows(NullPointerException.class, () -> persisted(mapper, null));
        assertThrows(IllegalArgumentException.class, () -> persisted(mapper, "").toModel());
        assertThrows(IllegalArgumentException.class, () -> persisted(mapper, "UPSERT").toModel());
        assertThrows(IllegalArgumentException.class, () -> persisted(symbol(CodeFactKind.METHOD, Optional.empty()), "UPDATE").toModel());
    }

    private static CodeFactDetails details(SymbolDocument symbol, Optional<MapperStatementKind> operation) {
        SelectedGeneration selected = new SelectedGeneration(REPOSITORY, REVISION, GENERATION, new ManifestDigest("b".repeat(64)));
        return new CodeFactDetails(selected, symbol.fact(), symbol.range(), symbol.annotations(), operation);
    }

    private static SymbolPersistence persisted(SymbolDocument symbol, String operation) {
        return new SymbolPersistence(symbol.repositoryId(), symbol.generationId(), symbol.fact(), symbol.kind(), symbol.owner(),
                symbol.name(), symbol.signature(), symbol.declaredType(), symbol.modifiers(), symbol.annotations(),
                symbol.sourceArtifactId(), symbol.range(), operation);
    }

    private static SymbolDocument symbol(CodeFactKind kind, Optional<MapperStatementKind> operation) {
        SourceTypeIdentity type = new SourceTypeIdentity(new JavaTypeIdentity("example", "Mapper"), RANGE.sourceFile());
        CodeFactIdentity identity = new CodeFactIdentity(REPOSITORY, REVISION, kind, kind == CodeFactKind.MAPPER_STATEMENT
                ? new MapperStatementIdentity("example.Mapper", "change", RANGE.sourceFile()) : new MethodTarget(type, "change", List.of()));
        return new SymbolDocument(REPOSITORY, GENERATION, new CodeFact(CodeFactId.from(identity), identity), kind,
                "example.Mapper", "change", identity.canonicalForm(), new DeclaredType("void"), Set.of(), List.of(),
                new SourceArtifactId("c".repeat(64)), RANGE, operation);
    }
}
