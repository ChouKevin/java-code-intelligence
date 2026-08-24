package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCall;
import com.java.semantic.semantic.domain.SemanticCallResolution;
import com.java.semantic.semantic.domain.SemanticCallSite;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticIncomingCallResult;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticReferenceAnchor;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import com.java.semantic.semantic.domain.SemanticResolutionOrigin;
import com.java.semantic.semantic.domain.SemanticSourceClassification;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdtLsRepositoryIndexExporterSemanticSessionTest {

    @TempDir
    Path repository;

    @Test
    void exports_methods_without_invocations_after_proving_the_semantic_workspace_is_available() throws IOException {
        writeSource("package sample; class NoCalls { void work() { } }");
        RecordingSemanticService semanticService = new RecordingSemanticService(false);

        new JdtLsRepositoryIndexExporter(semanticService).export(new RepositoryId("no-calls"), revision(),
                new GenerationId("no-calls-generation"), new FullIndexPlanner().plan(repository));

        assertThat(semanticService.workspaceChecks()).isPositive();
    }

    @Test
    void does_not_reuse_a_previous_exports_semantic_resolution_when_the_next_export_is_unresolved() throws IOException {
        writeSource("package sample; class Calls { void work() { helper(); } void helper() { } }");
        JdtLsRepositoryIndexExporter exporter = new JdtLsRepositoryIndexExporter(new RecordingSemanticService(true));

        exporter.export(new RepositoryId("resolved"), revision(), new GenerationId("resolved-generation"),
                new FullIndexPlanner().plan(repository));

        assertThatThrownBy(() -> exporter.export(new RepositoryId("unresolved"), revision(), new GenerationId("unresolved-generation"),
                new FullIndexPlanner().plan(repository)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JDT LS did not resolve any export call site");
    }

    @Test
    void isolates_simultaneous_exports_on_the_same_exporter_instance() throws Exception {
        Path resolvedRepository = repository.resolve("resolved");
        Path unresolvedRepository = repository.resolve("unresolved");
        writeSource(resolvedRepository, "package sample; class Calls { void work() { helper(); } void helper() { } }");
        writeSource(unresolvedRepository, "package sample; class Calls { void work() { helper(); } void helper() { } }");
        JdtLsRepositoryIndexExporter exporter = new JdtLsRepositoryIndexExporter(new RecordingSemanticService(true,
                new CyclicBarrier(2)));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<SourceIndexBatch>> resolved = executor.submit(() -> exporter.export(new RepositoryId("resolved"), revision(),
                    new GenerationId("resolved-generation"), new FullIndexPlanner().plan(resolvedRepository)));
            Future<List<SourceIndexBatch>> unresolved = executor.submit(() -> exporter.export(new RepositoryId("unresolved"), revision(),
                    new GenerationId("unresolved-generation"), new FullIndexPlanner().plan(unresolvedRepository)));

            assertThat(resolved.get()).isNotEmpty();
            assertThatThrownBy(unresolved::get).hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("JDT LS did not resolve any export call site");
        }
    }

    @Test
    void rejects_a_plan_when_a_source_changes_before_syntax_extraction() throws IOException {
        writeSource("package sample; class Calls { void work() { } }");
        FullIndexPlan plan = new FullIndexPlanner().plan(repository);
        writeSource("package sample; class Calls { void changed() { } }");

        assertThatThrownBy(() -> new JdtLsRepositoryIndexExporter().export(new RepositoryId("changed-before"), revision(),
                new GenerationId("changed-before-generation"), plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planned source content changed before syntax extraction: src/main/java/sample/Calls.java");
    }

    @Test
    void rejects_a_plan_when_a_source_changes_during_syntax_extraction() throws IOException {
        writeSource("package sample; class Calls { void work() { } }");
        FullIndexPlan plan = new FullIndexPlanner().plan(repository);
        Path source = repository.resolve("src/main/java/sample/Calls.java");
        JdtLsRepositoryIndexExporter exporter = new JdtLsRepositoryIndexExporter(
                new MutatingSyntaxExtractionService(source), new SyntaxSymbolProjector(), new SemanticRelationProjector(),
                new EntryPointProjector(), new SearchProjector(), SemanticCallTargetResolver.syntaxOnly());

        assertThatThrownBy(() -> exporter.export(new RepositoryId("changed-during"), revision(),
                new GenerationId("changed-during-generation"), plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planned source content changed after syntax extraction: src/main/java/sample/Calls.java");
    }

    private void writeSource(String source) throws IOException {
        writeSource(repository, source);
    }

    private static void writeSource(Path root, String source) throws IOException {
        Path file = root.resolve("src/main/java/sample/Calls.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static RepositoryRevision revision() {
        return new RepositoryRevision("a".repeat(40));
    }

    private static final class MutatingSyntaxExtractionService extends JdtSyntaxExtractionService {
        private final Path source;

        private MutatingSyntaxExtractionService(Path source) {
            this.source = source;
        }

        @Override
        public RepositorySyntax extract(Path repositoryRoot) {
            RepositorySyntax syntax = super.extract(repositoryRoot);
            try {
                Files.writeString(source, "package sample; class Calls { void changed() { } }");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return syntax;
        }
    }

    private static final class RecordingSemanticService implements JavaSemanticService {
        private final boolean resolveFirstRepository;
        private final Optional<CyclicBarrier> resolutionBarrier;
        private int workspaceChecks;

        private RecordingSemanticService(boolean resolveFirstRepository) {
            this(resolveFirstRepository, Optional.empty());
        }

        private RecordingSemanticService(boolean resolveFirstRepository, CyclicBarrier resolutionBarrier) {
            this(resolveFirstRepository, Optional.of(resolutionBarrier));
        }

        private RecordingSemanticService(boolean resolveFirstRepository, Optional<CyclicBarrier> resolutionBarrier) {
            this.resolveFirstRepository = resolveFirstRepository;
            this.resolutionBarrier = resolutionBarrier;
        }

        int workspaceChecks() {
            return workspaceChecks;
        }

        @Override
        public SemanticSourceClassification classifySource(RepositorySnapshot snapshot, SemanticMethod method) {
            workspaceChecks++;
            return new SemanticSourceClassification.LocalSource("src/main/java/sample/Calls.java");
        }

        @Override
        public List<SemanticReferenceLocation> findReferences(RepositorySnapshot snapshot, SemanticReferenceAnchor anchor) {
            return List.of();
        }

        @Override
        public SemanticMethod resolveExactMethod(RepositorySnapshot snapshot, SemanticDeclarationAnchor anchor) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public SemanticCallResolution resolveCallResolutionAt(RepositorySnapshot snapshot, SemanticMethod caller,
                                                               SemanticCallSite callSite) {
            resolutionBarrier.ifPresent(RecordingSemanticService::awaitBothExports);
            if (!resolveFirstRepository || "unresolved".equals(snapshot.repositoryId().value())) {
                return SemanticCallResolution.unresolved();
            }
            SemanticRange range = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(0, 1));
            SemanticMethod target = new SemanticMethod(caller.packageName(), caller.className(), "helper", List.of(), "void",
                    new SemanticLocation(snapshot.root().resolve("src/main/java/sample/Calls.java").toUri().toString(), range, range));
            return SemanticCallResolution.resolved(new SemanticCall(Optional.of(target), "sample.Calls.helper()", List.of(), false,
                    SemanticResolutionOrigin.DEFINITION_FALLBACK));
        }

        @Override
        public List<SemanticCall> outgoingCalls(RepositorySnapshot snapshot, SemanticMethod method) {
            return List.of();
        }

        @Override
        public SemanticIncomingCallResult incomingCalls(RepositorySnapshot snapshot, SemanticMethod callee) {
            return SemanticIncomingCallResult.empty();
        }

        @Override
        public SemanticImplementationResult implementations(RepositorySnapshot snapshot, SemanticMethod method) {
            return new SemanticImplementationResult(List.of(), List.of());
        }

        private static void awaitBothExports(CyclicBarrier barrier) {
            try {
                barrier.await();
            } catch (Exception exception) {
                throw new IllegalStateException("semantic export concurrency synchronization failed", exception);
            }
        }
    }
}
