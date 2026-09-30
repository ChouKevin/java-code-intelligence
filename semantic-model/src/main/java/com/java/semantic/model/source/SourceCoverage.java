package com.java.semantic.model.source;


/** Objective counts, not a completeness or runtime-use assertion. */
public record SourceCoverage(long readableCode, long excludedOrUnsupported, long extractionIssues,
        long unresolvedSemanticEvidence) {
    public SourceCoverage {
        if (readableCode < 0 || excludedOrUnsupported < 0 || extractionIssues < 0 || unresolvedSemanticEvidence < 0) {
            throw new IllegalArgumentException("source coverage counts cannot be negative");
        }
    }
}
