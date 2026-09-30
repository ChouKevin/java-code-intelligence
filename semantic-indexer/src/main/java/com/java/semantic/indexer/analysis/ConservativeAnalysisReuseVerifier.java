package com.java.semantic.indexer.analysis;

import com.java.semantic.model.index.SealedGeneration;
import java.util.Objects;

/** Reuses only a candidate whose independently reproduced effective inputs are identical. */
public final class ConservativeAnalysisReuseVerifier implements AnalysisReuseVerifier {
    private final CandidateMatcher matcher;

    public ConservativeAnalysisReuseVerifier(CandidateMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "candidate matcher is required");
    }

    @Override
    public boolean matches(SealedGeneration candidate, AnalysisTarget target) {
        Objects.requireNonNull(candidate, "candidate is required");
        Objects.requireNonNull(target, "target is required");
        if (!candidate.selected().repositoryId().equals(target.snapshot().repositoryId())
                || !candidate.selected().revision().equals(target.snapshot().revision())
                || !candidate.fingerprint().digest().equals(candidate.analysisEvidence().fingerprintDigest())) {
            return false;
        }
        return matcher.matches(candidate, target);
    }

    @FunctionalInterface
    public interface CandidateMatcher {
        boolean matches(SealedGeneration candidate, AnalysisTarget target);
    }
}
