package com.java.semantic.syntax.application.concept;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.syntax.domain.RepositorySyntax;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 從 mapper evidence index 投影 MAPPER_STATEMENT 概念 */
public final class MapperStatementConceptProvider implements ConceptProvider {

    private static final String PROVIDER_ID = "mapper-statement";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<ConceptKind> supportedKinds() {
        return Set.of(ConceptKind.MAPPER_STATEMENT);
    }

    @Override
    public ConceptProviderProjection project(RepositorySyntax syntax) {
        RepositorySyntax repositorySyntax = Objects.requireNonNull(syntax, "syntax is required");
        List<ConceptCatalogEntry> entries = repositorySyntax.mapperEvidenceIndex()
                .map(index -> index.statements().stream()
                        .map(statement -> entry(statement, repositorySyntax))
                        .toList())
                .orElseGet(List::of);
        return new ConceptProviderProjection(entries, List.of());
    }

    private static ConceptCatalogEntry entry(
            MapperStatementEvidence statement,
            RepositorySyntax syntax) {
        MapperStatementKey statementKey = statement.identity().statementKey();
        MapperStatementConceptIdentity identity = new MapperStatementConceptIdentity(statementKey);
        MapperStatementVariantEvidenceIdentity variantIdentity =
                new MapperStatementVariantEvidenceIdentity(statement.identity());
        String displayValue = statementKey.namespace() + "#" + statementKey.statementId();
        MapperStatementMethodMapping methodMapping = MapperStatementMethodMapping.fromSyntax(identity, syntax);
        return new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                displayValue,
                "",
                Optional.of(statementKey.namespace()),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity, variantIdentity),
                Optional.empty(),
                Optional.empty(),
                Optional.of(methodMapping),
                Optional.empty());
    }

}
