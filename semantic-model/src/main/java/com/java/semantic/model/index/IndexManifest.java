package com.java.semantic.model.index;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Digest-bound provenance for one sealed database; compatibility is checked by its consumer. */
public record IndexManifest(int formatVersion, SourceContext context, String originFingerprint,
        String indexGeneration, String sourceManifestDigest, String inventoryDigest,
        IndexContract.Profile profile, IndexContract.Coverage coverage, long databaseBytes,
        String databaseDigest, Instant preparedAt, Optional<String> analysisPlanDigest) {
    public IndexManifest {
        ModelValidation.require(formatVersion > 0, "index format must be positive");
        context = Objects.requireNonNull(context, "source context");
        originFingerprint = ModelValidation.sha256(originFingerprint, "approved origin fingerprint");
        indexGeneration = IndexContract.requireGeneration(indexGeneration);
        sourceManifestDigest = ModelValidation.sha256(sourceManifestDigest, "source manifest digest");
        inventoryDigest = ModelValidation.sha256(inventoryDigest, "inventory digest");
        profile = Objects.requireNonNull(profile, "index profile");
        coverage = Objects.requireNonNull(coverage, "index coverage");
        ModelValidation.require(databaseBytes > 0, "sealed database must have content");
        databaseDigest = ModelValidation.sha256(databaseDigest, "database digest");
        preparedAt = Objects.requireNonNull(preparedAt, "prepared at");
        analysisPlanDigest = Objects.requireNonNull(analysisPlanDigest, "analysis plan digest");
        analysisPlanDigest.ifPresent(value -> ModelValidation.sha256(value, "analysis plan digest"));
        if (IndexContract.supportedProfile(profile)) {
            ModelValidation.require(IndexContract.supportsRelations(profile) == analysisPlanDigest.isPresent(),
                    "analysis plan digest must match the known profile capability");
        }
    }

    public boolean compatible() {
        return formatVersion == IndexContract.FORMAT_VERSION && IndexContract.supportedProfile(profile);
    }
}
