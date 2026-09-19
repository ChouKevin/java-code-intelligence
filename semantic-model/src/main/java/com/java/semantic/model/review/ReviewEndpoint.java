package com.java.semantic.model.review;

import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.SealedGeneration;
import java.util.Objects;

/** One sealed semantic generation and the exact Git snapshot for a review side. */
public record ReviewEndpoint(SealedGeneration generation, GitSnapshotId snapshotId) {

    public ReviewEndpoint {
        generation = Objects.requireNonNull(generation, "review endpoint generation is required");
        snapshotId = Objects.requireNonNull(snapshotId, "review endpoint snapshot id is required");
    }
}
