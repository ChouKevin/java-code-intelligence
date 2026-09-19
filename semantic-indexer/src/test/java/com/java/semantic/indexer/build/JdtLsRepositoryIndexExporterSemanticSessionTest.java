package com.java.semantic.indexer.build;

import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
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
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdtLsRepositoryIndexExporterSemanticSessionTest {

    @TempDir
    Path repository;

    @Test
    void preserves_a_semantically_unresolved_call_when_syntax_supplies_an_internal_target() throws IOException {
        writeSource("package sample; class Calls { void work() { resolved(); unresolved(); } void resolved() { } void unresolved() { } }");
        RepositoryId repositoryId = new RepositoryId("mixed");
        FullIndexPlan plan = new FullIndexPlanner().plan(repository);
        RepositoryIndexExport export = JdtLsRepositoryIndexExporter.production().export(
                new GenerationWriteContext(repositoryId, new GenerationId("mixed-generation"), "mixed-job"),
                prepared(repositoryId, plan, new RecordingSemanticService(true)));

        long unresolvedCalls = export.batches().stream().flatMap(batch -> batch.relations().stream())
                .filter(relation -> relation.target() instanceof RelationTarget.External external
                        && external.target() instanceof ExternalTarget.UnresolvedCall).count();
        assertThat(unresolvedCalls).isEqualTo(1L);
        assertThat(export.analysisEvidence().resolution().unresolved()).isEqualTo(1L);
        assertThat(export.analysisEvidence().resolution().resolved()).isEqualTo(1L);
    }

    @Test
    void rejects_no_invocation_export_when_the_real_declaration_probe_is_unavailable() throws IOException {
        writeSource("package sample; class Calls { void work() { } }");
        RepositoryId repositoryId = new RepositoryId("no-calls");
        FullIndexPlan plan = new FullIndexPlanner().plan(repository);

        assertThatThrownBy(() -> JdtLsRepositoryIndexExporter.production().export(
                new GenerationWriteContext(repositoryId, new GenerationId("no-calls-generation"), "no-calls-job"),
                prepared(repositoryId, plan, new RecordingSemanticService(false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JDT LS did not classify an owned export declaration as repository-local");
    }

    private void writeSource(String source) throws IOException {
        Path file = repository.resolve("src/main/java/sample/Calls.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private PreparedAnalysis prepared(RepositoryId repositoryId, FullIndexPlan plan, JavaSemanticService semanticService) {
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, repository, revision());
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, digest(), digest(), digest(), digest(),
                List.of(new AnalysisInputs.Project(".", digest(), Map.of(), List.of(),
                        List.of(new AnalysisInputs.Root("src/main/java", "MAIN", true, List.of())), List.of(), List.of())));
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION,
                fingerprint.digest(), "READY", List.of(new SemanticAnalysisEvidence.ProjectProof(".", true,
                List.of("src/main/java"))), new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        return new PreparedAnalysis() {
            @Override
            public RepositorySnapshot snapshot() {
                return snapshot;
            }

            @Override
            public FullIndexPlan plan() {
                return plan;
            }

            @Override
            public AnalysisFingerprint fingerprint() {
                return fingerprint;
            }

            @Override
            public SemanticAnalysisEvidence readinessEvidence() {
                return evidence;
            }

            @Override
            public JavaSemanticService semanticService() {
                return semanticService;
            }

            @Override
            public void verifyUnchangedInputs() {
            }

            @Override
            public void close() {
            }
        };
    }

    private static RepositoryRevision revision() {
        return new RepositoryRevision("a".repeat(40));
    }

    private static String digest() {
        return "a".repeat(64);
    }

    private static final class RecordingSemanticService implements JavaSemanticService {
        private final boolean available;
        private int resolutions;

        private RecordingSemanticService(boolean available) {
            this.available = available;
        }

        @Override
        public SemanticSourceClassification classifySource(RepositorySnapshot snapshot, SemanticMethod method) {
            return available ? new SemanticSourceClassification.LocalSource("src/main/java/sample/Calls.java")
                    : SemanticSourceClassification.UnprovableUri.INSTANCE;
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
            resolutions++;
            if (resolutions == 2) {
                return SemanticCallResolution.unresolved();
            }
            SemanticRange range = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(0, 1));
            SemanticMethod target = new SemanticMethod(caller.packageName(), caller.className(), "resolved", List.of(), "void",
                    new SemanticLocation(snapshot.root().resolve("src/main/java/sample/Calls.java").toUri().toString(), range, range));
            return SemanticCallResolution.resolved(new SemanticCall(Optional.of(target), "sample.Calls.resolved()", List.of(), false,
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
    }
}
