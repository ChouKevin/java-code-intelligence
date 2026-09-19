package com.java.semantic.model.index;

import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;

/** A selected immutable generation whose analysis identity is backed by evidence. */
public record SealedGeneration(
        SelectedGeneration selected,
        AnalysisFingerprint fingerprint,
        SemanticAnalysisEvidence analysisEvidence) {

    public SealedGeneration {
        selected = Objects.requireNonNull(selected, "selected generation is required");
        fingerprint = Objects.requireNonNull(fingerprint, "analysis fingerprint is required");
        analysisEvidence = Objects.requireNonNull(analysisEvidence, "analysis evidence is required");
        ModelValidation.require(fingerprint.digest().equals(analysisEvidence.fingerprintDigest()),
                "analysis evidence fingerprint must match generation fingerprint");
    }
}
