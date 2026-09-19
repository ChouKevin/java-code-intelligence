package com.java.semantic.indexer.job;

import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.CapturedReviewBaseline;
import com.java.semantic.model.review.ReviewId;
import java.util.Objects;
import java.util.Optional;

/** Immutable review admission data plus the atomically advancing preparation state. */
public record ReviewJobPayload(
        ReviewId reviewId,
        CapturedReviewBaseline baseline,
        RepositoryRevision requestedRevision,
        ReviewBuildTargets reservedTargets,
        ReviewPreparationStage stage,
        Optional<SealedGeneration> a,
        Optional<SealedGeneration> b,
        Optional<GitComparisonId> comparisonId,
        Optional<GitSnapshotId> previousSnapshotId,
        Optional<GitSnapshotId> currentSnapshotId) {
    public ReviewJobPayload {
        reviewId = Objects.requireNonNull(reviewId, "review id is required");
        baseline = Objects.requireNonNull(baseline, "review baseline is required");
        requestedRevision = Objects.requireNonNull(requestedRevision, "requested revision is required");
        reservedTargets = Objects.requireNonNull(reservedTargets, "reserved review targets are required");
        stage = Objects.requireNonNull(stage, "review preparation stage is required");
        a = Objects.requireNonNull(a, "review A generation is required");
        b = Objects.requireNonNull(b, "review B generation is required");
        comparisonId = Objects.requireNonNull(comparisonId, "review comparison id is required");
        previousSnapshotId = Objects.requireNonNull(previousSnapshotId, "previous snapshot id is required");
        currentSnapshotId = Objects.requireNonNull(currentSnapshotId, "current snapshot id is required");
    }
}
