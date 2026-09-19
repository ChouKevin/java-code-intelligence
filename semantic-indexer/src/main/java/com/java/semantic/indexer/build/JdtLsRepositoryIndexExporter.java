package com.java.semantic.indexer.build;

import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceExtractionStatus;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;

/** Production exporter: every export is bound to a prepared semantic lease. */
public final class JdtLsRepositoryIndexExporter implements RepositoryIndexExporter {
    private final JdtSyntaxExtractionService syntaxExtractionService;
    private final SyntaxSymbolProjector symbolProjector;
    private final SemanticRelationProjector relationProjector;
    public static JdtLsRepositoryIndexExporter production() {
        return new JdtLsRepositoryIndexExporter(new JdtSyntaxExtractionService(), new SyntaxSymbolProjector(),
                new SemanticRelationProjector(), new EntryPointProjector(), new SearchProjector());
    }

    private final EntryPointProjector entryPointProjector;
    private final SearchProjector searchProjector;

    public JdtLsRepositoryIndexExporter(JdtSyntaxExtractionService syntaxExtractionService, SyntaxSymbolProjector symbolProjector,
                                        SemanticRelationProjector relationProjector, EntryPointProjector entryPointProjector,
                                        SearchProjector searchProjector) {
        this.syntaxExtractionService = Objects.requireNonNull(syntaxExtractionService, "syntax extraction service is required");
        this.symbolProjector = Objects.requireNonNull(symbolProjector, "symbol projector is required");
        this.relationProjector = Objects.requireNonNull(relationProjector, "relation projector is required");
        this.entryPointProjector = Objects.requireNonNull(entryPointProjector, "entry point projector is required");
        this.searchProjector = Objects.requireNonNull(searchProjector, "search projector is required");
    }


    @Override
    public RepositoryIndexExport export(GenerationWriteContext context, PreparedAnalysis analysis) {
        GenerationWriteContext requiredContext = Objects.requireNonNull(context, "generation write context is required");
        PreparedAnalysis prepared = Objects.requireNonNull(analysis, "prepared analysis is required");
        RepositorySnapshot snapshot = prepared.snapshot();
        if (!requiredContext.repositoryId().equals(snapshot.repositoryId())) {
            throw new IllegalArgumentException("prepared analysis repository differs from generation context");
        }
        prepared.verifyUnchangedInputs();
        FullIndexPlan plan = prepared.plan();
        SemanticCallTargetResolver resolver = new JdtLsSemanticCallTargetResolver(prepared.semanticService());
        validatePlannedSources(plan, "before syntax extraction");
        RepositorySyntax syntax = plan.importedInputs()
                ? syntaxExtractionService.extract(plan.repositoryRoot(), plan.sourceRoots(), plan.effectiveCompilerOptions())
                : syntaxExtractionService.extract(plan.repositoryRoot());
        validatePlannedSources(plan, "after syntax extraction");
        Map<String, Optional<SourceIndexIssue>> extractionIssues = extractionIssues(syntax);
        semanticWorkspaceProbe(syntax).ifPresent(method -> resolver.verifySemanticWorkspace(snapshot, method));
        List<SourceIndexBatch> batches = plan.sources().stream().flatMap(source -> {
            List<SymbolDocument> symbols = new ArrayList<>(symbolProjector.project(snapshot.repositoryId(), snapshot.revision(),
                    requiredContext.generationId(), syntax, source.sourcePath(), source.contentArtifact()));
            symbols.addAll(symbolProjector.projectMapperStatements(snapshot.repositoryId(), snapshot.revision(),
                    requiredContext.generationId(), syntax, source.sourcePath(), source.contentArtifact()));
            symbols = symbols.stream().sorted(Comparator.comparing(document -> document.fact().id().value())).toList();
            List<RelationDocument> relations = relationProjector.project(snapshot.repositoryId(), snapshot.revision(),
                    requiredContext.generationId(), syntax, source.sourcePath(), source.contentArtifact(), plan.repositoryRoot(),
                    snapshot, resolver);
            List<EntryPointDocument> entryPoints = entryPointProjector.project(snapshot.repositoryId(), snapshot.revision(),
                    requiredContext.generationId(), syntax, source.sourcePath());
            SourceIndexScope sourceScope = SourceIndexScope.from(symbols);
            return split(snapshot.repositoryId(), requiredContext.generationId(), source.sourcePath(), source.contentArtifact(),
                    extractionIssues.getOrDefault(source.sourcePath(), Optional.empty()), sourceScope, symbols, relations, entryPoints,
                    searchProjector.project(symbols, relations, entryPoints)).stream();
        }).toList();
        prepared.verifyUnchangedInputs();
        SemanticAnalysisEvidence readiness = prepared.readinessEvidence();
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(readiness.contractVersion(), readiness.fingerprintDigest(),
                readiness.buildStatus(), readiness.projects(), resolver.snapshot(), readiness.limitations());
        return new RepositoryIndexExport(batches, evidence);
    }

    private static void validatePlannedSources(FullIndexPlan plan, String boundary) {
        for (FullIndexPlan.SourceInput source : plan.sources()) {
            try {
                String currentContent = Files.readString(source.path());
                if (!source.contentArtifact().utf8Content().equals(currentContent)) {
                    throw new IllegalStateException("planned source content changed " + boundary + ": " + source.sourcePath());
                }
            } catch (NoSuchFileException exception) {
                throw new IllegalStateException("planned source missing " + boundary + ": " + source.sourcePath(), exception);
            } catch (IOException exception) {
                throw new UncheckedIOException("unable to validate planned source " + boundary + ": " + source.sourcePath(), exception);
            }
        }
    }

    private static java.util.Optional<SourceMethodMetadata> semanticWorkspaceProbe(RepositorySyntax syntax) {
        return syntax.sourceTypes().stream()
                .flatMap(type -> type.members().methods().stream())
                .filter(method -> method.analysisTarget().target().isPresent())
                .sorted(Comparator.comparing((SourceMethodMetadata method) -> method.declarationLocation().sourceFile())
                        .thenComparing(SourceMethodMetadata::name)
                        .thenComparing(method -> String.join(",", method.paramTypes())))
                .findFirst();
    }

    static List<SourceIndexBatch> split(RepositoryId repositoryId, GenerationId generationId, String sourcePath,
                                        SourceArtifactDocument artifact, Optional<SourceIndexIssue> extractionIssue, SourceIndexScope sourceScope,
                                        List<SymbolDocument> symbols,
                                        List<RelationDocument> relations, List<EntryPointDocument> entryPoints,
                                        List<SearchDocument> search) {
        List<SourceIndexBatch> batches = new ArrayList<>();
        List<Object> documents = new ArrayList<>();
        documents.addAll(symbols);
        documents.addAll(relations);
        documents.addAll(entryPoints);
        documents.addAll(search);
        for (int start = 0; start < documents.size() || (documents.isEmpty() && start == 0); start += SourceIndexBatch.MAX_QUERY_DOCUMENTS) {
            int end = Math.min(start + SourceIndexBatch.MAX_QUERY_DOCUMENTS, documents.size());
            List<Object> chunk = documents.subList(start, end);
            List<SymbolDocument> chunkSymbols = chunk.stream()
                    .filter(SymbolDocument.class::isInstance).map(SymbolDocument.class::cast).toList();
            List<RelationDocument> chunkRelations = chunk.stream()
                    .filter(RelationDocument.class::isInstance).map(RelationDocument.class::cast).toList();
            List<EntryPointDocument> chunkEntryPoints = chunk.stream()
                    .filter(EntryPointDocument.class::isInstance).map(EntryPointDocument.class::cast).toList();
            List<SearchDocument> chunkSearch = chunk.stream()
                    .filter(SearchDocument.class::isInstance).map(SearchDocument.class::cast).toList();
            batches.add(new SourceIndexBatch(repositoryId, generationId, sourcePath, batches.size(), artifact, extractionIssue, sourceScope, chunkSymbols,
                    chunkRelations, chunkEntryPoints, chunkSearch));
            if (documents.isEmpty()) {
                break;
            }
        }
        return List.copyOf(batches);
    }

    static Map<String, Optional<SourceIndexIssue>> extractionIssues(RepositorySyntax syntax) {
        RepositorySyntax requiredSyntax = Objects.requireNonNull(syntax, "repository syntax is required");
        Map<String, Optional<SourceIndexIssue>> result = new LinkedHashMap<>();
        for (com.java.semantic.syntax.domain.SourceExtractionOutcome outcome : requiredSyntax.extractionOutcomes()) {
            if (result.containsKey(outcome.sourceFile())) {
                throw new IllegalArgumentException("duplicate extraction outcome for " + outcome.sourceFile());
            }
            Optional<SourceIndexIssue> issue = outcome.status() == SourceExtractionStatus.EXTRACTED
                    ? Optional.empty() : outcome.reasonCode().map(code -> new SourceIndexIssue(outcome.sourceFile(), code));
            result.put(outcome.sourceFile(), issue);
        }
        return Map.copyOf(result);
    }
}
