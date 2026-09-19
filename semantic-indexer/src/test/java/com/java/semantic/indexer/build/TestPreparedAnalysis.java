package com.java.semantic.indexer.build;

import com.java.semantic.indexer.analysis.PreparedAnalysis;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.util.List;
import java.util.Map;

final class TestPreparedAnalysis implements PreparedAnalysis {
    private static final String DIGEST = "a".repeat(64);
    private final RepositorySnapshot snapshot;
    private final FullIndexPlan plan;
    private final AnalysisFingerprint fingerprint;
    private final SemanticAnalysisEvidence evidence;

    static TestPreparedAnalysis forSnapshot(RepositorySnapshot snapshot, FullIndexPlan plan) {
        return new TestPreparedAnalysis(snapshot, plan);
    }

    private TestPreparedAnalysis(RepositorySnapshot snapshot, FullIndexPlan plan) {
        this.snapshot = snapshot;
        this.plan = plan;
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, DIGEST, DIGEST, DIGEST, DIGEST,
                List.of(new AnalysisInputs.Project("project", DIGEST, Map.of(), List.of(),
                        List.of(new AnalysisInputs.Root("src", "MAIN", true, List.of())), List.of(), List.of())));
        fingerprint = AnalysisFingerprint.from(inputs);
        evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, fingerprint.digest(), "READY",
                List.of(new SemanticAnalysisEvidence.ProjectProof("project", true, List.of("src"))),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
    }

    @Override public RepositorySnapshot snapshot() { return snapshot; }
    @Override public FullIndexPlan plan() { return plan; }
    @Override public AnalysisFingerprint fingerprint() { return fingerprint; }
    @Override public SemanticAnalysisEvidence readinessEvidence() { return evidence; }
    @Override public JavaSemanticService semanticService() { throw new UnsupportedOperationException("test exporter does not use semantic service"); }
    @Override public void verifyUnchangedInputs() { }
    @Override public void close() { }
}
