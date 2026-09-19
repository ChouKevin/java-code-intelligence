package com.java.semantic.indexer.analysis;

import com.java.semantic.indexer.build.FullIndexPlan;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;

/** Owns the isolated JDT lease backing a complete semantic preparation. */
public interface PreparedAnalysis extends AutoCloseable {
    RepositorySnapshot snapshot();

    FullIndexPlan plan();

    AnalysisFingerprint fingerprint();

    SemanticAnalysisEvidence readinessEvidence();

    JavaSemanticService semanticService();

    void verifyUnchangedInputs();

    /** Narrows export work while retaining the complete preparation's attestation and lease. */
    default PreparedAnalysis forExportPlan(FullIndexPlan exportPlan) {
        if (plan().equals(exportPlan)) {
            return this;
        }
        return new PreparedAnalysisView(this, exportPlan);
    }

    @Override
    void close();
}
