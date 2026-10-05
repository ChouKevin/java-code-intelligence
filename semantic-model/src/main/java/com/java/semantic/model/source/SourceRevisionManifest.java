package com.java.semantic.model.source;

import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideInfo;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record SourceRevisionManifest(int formatVersion, int policyVersion, SourceContext context, Instant preparedAt,
        GuideInfo projectGuide, Coverage coverage, String inventoryDigest) {

    public static final int FORMAT_VERSION = 1;
    public static final int POLICY_VERSION = 2;

    public SourceRevisionManifest {
        ModelValidation.require(formatVersion == FORMAT_VERSION, "unsupported source format version");
        ModelValidation.require(policyVersion == POLICY_VERSION, "unsupported source policy version");
        context = Objects.requireNonNull(context, "source context");
        preparedAt = Objects.requireNonNull(preparedAt, "prepared at");
        projectGuide = Objects.requireNonNull(projectGuide, "project guide");
        coverage = Objects.requireNonNull(coverage, "source coverage");
        inventoryDigest = ModelValidation.sha256(inventoryDigest, "inventory digest");
    }

    public record Coverage(long readableFileCount, long excludedFileCount, Map<EntryStatus, Long> unsupportedCounts) {

        public Coverage {
            ModelValidation.require(readableFileCount >= 0 && excludedFileCount >= 0,
                    "source file counts must not be negative");
            unsupportedCounts = Map.copyOf(Objects.requireNonNull(unsupportedCounts, "unsupported counts"));
            for (Map.Entry<EntryStatus, Long> entry : unsupportedCounts.entrySet()) {
                ModelValidation.require(entry.getKey() != EntryStatus.TEXT && entry.getValue() >= 0,
                        "unsupported counts require unsupported states and nonnegative counts");
            }
        }
    }
}
