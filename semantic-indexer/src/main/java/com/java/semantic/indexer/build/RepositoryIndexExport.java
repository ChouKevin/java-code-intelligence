package com.java.semantic.indexer.build;

import com.java.semantic.model.index.SemanticAnalysisEvidence;
import java.util.List;
import java.util.Objects;

/** Immutable semantic export bound to the prepared analysis that produced it. */
public record RepositoryIndexExport(List<SourceIndexBatch> batches, SemanticAnalysisEvidence analysisEvidence) {
    public RepositoryIndexExport {
        batches = List.copyOf(Objects.requireNonNull(batches, "export batches are required"));
        analysisEvidence = Objects.requireNonNull(analysisEvidence, "semantic analysis evidence is required");
    }
}
