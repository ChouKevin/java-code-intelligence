package com.java.semantic.indexer.build;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceExtractionStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Production exporter: syntax extraction stays in Indexer and model documents contain no JDT/LSP types. */
public final class JdtLsRepositoryIndexExporter implements RepositoryIndexExporter {
    private final JdtSyntaxExtractionService syntaxExtractionService;
    private final SyntaxSymbolProjector symbolProjector;
    private final SemanticRelationProjector relationProjector;
    private final EntryPointProjector entryPointProjector;
    private final SearchProjector searchProjector;
    private final SemanticCallTargetResolver semanticCallTargetResolver;

    public JdtLsRepositoryIndexExporter() {
        this(new JdtSyntaxExtractionService(), new SyntaxSymbolProjector(), new SemanticRelationProjector(),
                new EntryPointProjector(), new SearchProjector(), SemanticCallTargetResolver.syntaxOnly());
    }

    /** Production constructor: call projection is backed by the ready JDT LS workspace. */
    public JdtLsRepositoryIndexExporter(JavaSemanticService semanticService) {
        this(new JdtSyntaxExtractionService(), new SyntaxSymbolProjector(), new SemanticRelationProjector(),
                new EntryPointProjector(), new SearchProjector(), new JdtLsSemanticCallTargetResolver(semanticService));
    }

    JdtLsRepositoryIndexExporter(JdtSyntaxExtractionService syntaxExtractionService, SyntaxSymbolProjector symbolProjector,
                                 SemanticRelationProjector relationProjector, EntryPointProjector entryPointProjector,
                                 SearchProjector searchProjector, SemanticCallTargetResolver semanticCallTargetResolver) {
        this.syntaxExtractionService = Objects.requireNonNull(syntaxExtractionService, "syntax extraction service is required");
        this.symbolProjector = Objects.requireNonNull(symbolProjector, "symbol projector is required");
        this.relationProjector = Objects.requireNonNull(relationProjector, "relation projector is required");
        this.entryPointProjector = Objects.requireNonNull(entryPointProjector, "entry point projector is required");
        this.searchProjector = Objects.requireNonNull(searchProjector, "search projector is required");
        this.semanticCallTargetResolver = Objects.requireNonNull(semanticCallTargetResolver, "semantic call target resolver is required");
    }

    @Override
    public List<SourceIndexBatch> export(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                         FullIndexPlan plan) {
        semanticCallTargetResolver.beginExport();
        try {
            validatePlannedSources(plan, "before syntax extraction");
            RepositorySyntax syntax = syntaxExtractionService.extract(plan.repositoryRoot());
            validatePlannedSources(plan, "after syntax extraction");
            RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, plan.repositoryRoot(), revision);
            semanticWorkspaceProbe(syntax).ifPresent(method -> semanticCallTargetResolver.verifySemanticWorkspace(snapshot, method));
            List<SourceIndexBatch> batches = plan.sources().stream().flatMap(source -> {
            List<SymbolDocument> symbols = new ArrayList<>(symbolProjector.project(
                    repositoryId, revision, generationId, syntax, source.sourcePath(), source.contentArtifact()));
            symbols.addAll(symbolProjector.projectMapperStatements(repositoryId, revision, generationId, syntax,
                    source.sourcePath(), source.contentArtifact()));
            symbols = symbols.stream().sorted(java.util.Comparator.comparing(document -> document.fact().id().value())).toList();
            List<RelationDocument> relations = relationProjector.project(repositoryId, revision, generationId,
                    syntax, source.sourcePath(), source.contentArtifact(), plan.repositoryRoot(), snapshot, semanticCallTargetResolver);
            List<EntryPointDocument> entryPoints = entryPointProjector.project(repositoryId, revision,
                    generationId, syntax, source.sourcePath());
            SourceIndexScope sourceScope = SourceIndexScope.from(symbols);
            return split(repositoryId, generationId, source.sourcePath(), source.contentArtifact(), extractionIssue(syntax, source.sourcePath()), sourceScope, symbols, relations, entryPoints,
                    searchProjector.project(symbols, relations, entryPoints)).stream();
            }).toList();
            semanticCallTargetResolver.requireSemanticResolution();
            return batches;
        } finally {
            semanticCallTargetResolver.endExport();
        }
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

    private static Optional<SourceIndexIssue> extractionIssue(RepositorySyntax syntax, String sourcePath) {
        for (com.java.semantic.syntax.domain.SourceExtractionOutcome outcome : syntax.extractionOutcomes()) {
            if (sourcePath.equals(outcome.sourceFile())) {
                if (outcome.status() == SourceExtractionStatus.EXTRACTED) {
                    return Optional.empty();
                }
                return outcome.reasonCode().map(code -> new SourceIndexIssue(sourcePath, code));
            }
        }
        return Optional.empty();
    }
}
