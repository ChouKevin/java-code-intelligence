package com.java.semantic.indexer.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;
import java.util.Optional;

/** Immutable analysis selected before durable admission. */
public record IndexAnalysisIntent(IndexContract.Profile profile, Optional<String> analysisPlanDigest) {
    public IndexAnalysisIntent {
        profile = Objects.requireNonNull(profile);
        analysisPlanDigest = Objects.requireNonNull(analysisPlanDigest);
        ModelValidation.require(profile.equals(IndexContract.syntaxProfile()) || profile.equals(IndexContract.relationsProfile()),
                "unsupported accepted analysis profile");
        analysisPlanDigest.ifPresent(value -> ModelValidation.sha256(value, "analysis plan digest"));
        ModelValidation.require(IndexContract.supportsRelations(profile) == analysisPlanDigest.isPresent(), "analysis digest/profile mismatch");
    }
    public static IndexAnalysisIntent syntax() { return new IndexAnalysisIntent(IndexContract.syntaxProfile(), Optional.empty()); }
}
