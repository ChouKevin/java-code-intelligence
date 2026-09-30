package com.java.semantic.indexer.build;

import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Syntax-only projection fixture; storage tests supply synthetic analysis evidence, not a real JDT LS proof. */
final class TestSyntaxRepositoryIndexExporter {
    private final JdtSyntaxExtractionService extraction = new JdtSyntaxExtractionService();
    private final SyntaxSymbolProjector symbols = new SyntaxSymbolProjector();
    private final SemanticRelationProjector relations = new SemanticRelationProjector();
    private final EntryPointProjector entryPoints = new EntryPointProjector();
    private final SearchProjector search = new SearchProjector();

    List<SourceIndexBatch> export(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
            FullIndexPlan plan) {
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, plan.repositoryRoot(), revision);
        RepositorySyntax syntax = extraction.extract(plan.repositoryRoot());
        Map<String, Optional<SourceIndexIssue>> issues = JdtLsRepositoryIndexExporter.extractionIssues(syntax);
        return plan.sources().stream().flatMap(source -> {
            List<SymbolDocument> projected = new ArrayList<>(symbols.project(repositoryId, revision, generationId, syntax,
                    source.sourcePath(), source.contentArtifact()));
            projected.addAll(symbols.projectMapperStatements(repositoryId, revision, generationId, syntax,
                    source.sourcePath(), source.contentArtifact()));
            projected = projected.stream().sorted(Comparator.comparing(document -> document.fact().id().value())).toList();
            List<RelationDocument> projectedRelations = relations.project(repositoryId, revision, generationId, syntax,
                    source.sourcePath(), source.contentArtifact(), plan.repositoryRoot(), snapshot,
                    SemanticCallTargetResolver.syntaxOnly());
            List<EntryPointDocument> projectedEntryPoints = entryPoints.project(repositoryId, revision, generationId, syntax,
                    source.sourcePath());
            return JdtLsRepositoryIndexExporter.split(repositoryId, generationId, source.sourcePath(), source.contentArtifact(),
                    issues.getOrDefault(source.sourcePath(), Optional.empty()), SourceIndexScope.from(projected), projected,
                    projectedRelations, projectedEntryPoints, search.project(projected, projectedRelations, projectedEntryPoints)).stream();
        }).toList();
    }
}
