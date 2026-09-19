package com.java.semantic.indexer.analysis;

/** Opens and attests an endpoint-private semantic analysis lease. */
public interface RepositoryAnalysisPreparation {
    PreparedAnalysis prepare(AnalysisTarget target);
}
