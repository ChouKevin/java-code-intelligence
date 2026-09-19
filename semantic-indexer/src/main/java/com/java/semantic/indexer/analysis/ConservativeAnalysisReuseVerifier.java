package com.java.semantic.indexer.analysis;

import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.SealedGeneration;
import java.util.Objects;
import java.util.Optional;

/** Reuses only a candidate whose independently reproduced effective inputs are identical. */
public final class ConservativeAnalysisReuseVerifier implements AnalysisReuseVerifier {
    private final EffectiveInputReproducer reproducer;

    public ConservativeAnalysisReuseVerifier(EffectiveInputReproducer reproducer) {
        this.reproducer = Objects.requireNonNull(reproducer, "reproducer is required");
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
        Optional<AnalysisInputs> reproduced = reproducer.reproduce(target);
        if (reproduced.isEmpty()) {
            return false;
        }
        return candidate.fingerprint().equals(AnalysisFingerprint.from(reproduced.orElseThrow()));
    }

    @FunctionalInterface
    public interface EffectiveInputReproducer {
        Optional<AnalysisInputs> reproduce(AnalysisTarget target);
    }
}
