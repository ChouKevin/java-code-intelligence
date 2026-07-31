package com.java.semantic.syntax.application;

import com.java.semantic.identity.MethodTarget;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.ConceptIdentity.MapperStatementVariantEvidenceIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.RepositorySyntax;

import java.util.HashSet;
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
        MapperStatementConceptIdentity identity = new MapperStatementConceptIdentity(
                statement.identity().namespace(),
                statement.identity().statementId());
        MapperStatementVariantEvidenceIdentity variantIdentity =
                new MapperStatementVariantEvidenceIdentity(statement.identity());
        String canonicalValue = statement.identity().namespace() + "#" + statement.identity().statementId();
        MapperStatementMethodMapping methodMapping = MapperStatementMethodMapping.fromSyntax(identity, syntax);
        List<MethodTarget> mappedTargets = methodMapping.targets();
        return new ConceptCatalogEntry(
                PROVIDER_ID,
                identity,
                canonicalValue,
                canonicalValue,
                mapperTokensOf(statement, mappedTargets),
                "",
                Optional.of(statement.identity().namespace()),
                ConceptAuthority.SYNTAX_DECLARED,
                Set.of(identity, variantIdentity),
                Optional.of(methodMapping));
    }

    private static Set<String> mapperTokensOf(
            MapperStatementEvidence statement,
            List<MethodTarget> mappedTargets) {
        Set<String> tokens = new HashSet<>();
        tokens.addAll(ConceptSearchTokenizer.tokenize(statement.identity().namespace()));
        tokens.addAll(ConceptSearchTokenizer.tokenize(statement.identity().statementId()));
        for (MethodTarget target : mappedTargets) {
            tokens.addAll(ConceptSearchTokenizer.tokenize(target.className()));
            tokens.addAll(ConceptSearchTokenizer.tokenize(target.methodName()));
        }
        return Set.copyOf(tokens);
    }
}
