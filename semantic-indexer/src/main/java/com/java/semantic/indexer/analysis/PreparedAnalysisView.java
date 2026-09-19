package com.java.semantic.indexer.analysis;

import com.java.semantic.indexer.build.FullIndexPlan;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import java.util.Objects;

/** Lease-owning complete preparation with a deliberately narrowed export inventory. */
final class PreparedAnalysisView implements PreparedAnalysis {
    private final PreparedAnalysis complete;
    private final FullIndexPlan exportPlan;

    PreparedAnalysisView(PreparedAnalysis complete, FullIndexPlan exportPlan) {
        this.complete = Objects.requireNonNull(complete, "complete prepared analysis is required");
        this.exportPlan = Objects.requireNonNull(exportPlan, "export plan is required");
        if (!complete.plan().repositoryRoot().equals(exportPlan.repositoryRoot())) {
            throw new IllegalArgumentException("export plan belongs to a different repository root");
        }
    }

    @Override public RepositorySnapshot snapshot() { return complete.snapshot(); }
    @Override public FullIndexPlan plan() { return exportPlan; }
    @Override public AnalysisFingerprint fingerprint() { return complete.fingerprint(); }
    @Override public SemanticAnalysisEvidence readinessEvidence() { return complete.readinessEvidence(); }
    @Override public JavaSemanticService semanticService() { return complete.semanticService(); }
    @Override public void verifyUnchangedInputs() { complete.verifyUnchangedInputs(); }
    @Override public void close() { complete.close(); }
}
