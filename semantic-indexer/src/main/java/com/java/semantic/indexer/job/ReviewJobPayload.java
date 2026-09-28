package com.java.semantic.indexer.job;

import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewSelection;
import java.util.Objects;
import java.util.Optional;

/** Immutable request plus durable worker-resolved endpoints and advancing preparation state. */
public record ReviewJobPayload(
        ReviewId reviewId,
        ReviewSelection selection,
        Optional<ResolvedReviewEndpoints> resolvedEndpoints,
        Optional<ReviewBuildTargets> reservedTargets,
        ReviewPreparationStage stage,
        Optional<SealedGeneration> before,
        Optional<SealedGeneration> after,
        Optional<GitComparisonId> comparisonId,
        Optional<GitSnapshotId> previousSnapshotId,
        Optional<GitSnapshotId> currentSnapshotId) {
    public ReviewJobPayload {
        reviewId = Objects.requireNonNull(reviewId, "review id is required");
        selection = Objects.requireNonNull(selection, "review selection is required");
        resolvedEndpoints = Objects.requireNonNull(resolvedEndpoints, "resolved endpoints are required");
        reservedTargets = Objects.requireNonNull(reservedTargets, "reserved review targets are required");
        stage = Objects.requireNonNull(stage, "review preparation stage is required");
        before = Objects.requireNonNull(before, "review before generation is required");
        after = Objects.requireNonNull(after, "review after generation is required");
        comparisonId = Objects.requireNonNull(comparisonId, "review comparison id is required");
        previousSnapshotId = Objects.requireNonNull(previousSnapshotId, "previous snapshot id is required");
        currentSnapshotId = Objects.requireNonNull(currentSnapshotId, "current snapshot id is required");
        if (resolvedEndpoints.isPresent() != reservedTargets.isPresent()) {
            throw new IllegalArgumentException("resolved review endpoints and reserved targets must be persisted together");
        }
        if (stage != ReviewPreparationStage.RESOLVING && resolvedEndpoints.isEmpty()) {
            throw new IllegalArgumentException("review endpoints must be resolved before building");
        }
    }
}
