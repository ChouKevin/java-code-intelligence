package com.java.semantic.model.review;

import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable review membership contract. Persistence adapters own BSON encoding. */
public record ReviewManifestDocument(
        RepositoryId repositoryId,
        ReviewId reviewId,
        String ownerJobId,
        int reviewContractVersion,
        ReviewState state,
        ReviewSelection selection,
        Optional<ResolvedReviewEndpoints> resolvedEndpoints,
        Optional<ReviewEndpoint> before,
        Optional<ReviewEndpoint> after,
        Optional<GitComparisonId> comparisonId,
        Instant createdAt,
        Optional<Instant> publishedAt,
        Optional<String> failureCategory) {

    public ReviewManifestDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        reviewId = Objects.requireNonNull(reviewId, "review id is required");
        ownerJobId = ModelValidation.requiredText(ownerJobId, "review owner job id");
        ModelValidation.require(reviewContractVersion == IndexSchemaContract.REVIEW_MANIFEST_VERSION,
                "unsupported review manifest version");
        state = Objects.requireNonNull(state, "review state is required");
        selection = Objects.requireNonNull(selection, "review selection is required");
        resolvedEndpoints = Objects.requireNonNull(resolvedEndpoints, "resolved review endpoints are required");
        before = Objects.requireNonNull(before, "review before endpoint is required");
        after = Objects.requireNonNull(after, "review after endpoint is required");
        comparisonId = Objects.requireNonNull(comparisonId, "review comparison id is required");
        createdAt = Objects.requireNonNull(createdAt, "review creation time is required");
        publishedAt = Objects.requireNonNull(publishedAt, "review publication time is required");
        failureCategory = Objects.requireNonNull(failureCategory, "review failure category is required")
                .map(value -> ModelValidation.requiredText(value, "review failure category"));

        switch (state) {
            case READY -> requireReady(repositoryId, selection, resolvedEndpoints, before, after, comparisonId, publishedAt, failureCategory);
            case PREPARING -> requirePreparingOrFailed(before, after, comparisonId, publishedAt, failureCategory, false);
            case FAILED -> requirePreparingOrFailed(before, after, comparisonId, publishedAt, failureCategory, true);
        }
    }

    private static void requireReady(
            RepositoryId repositoryId,
            ReviewSelection selection,
            Optional<ResolvedReviewEndpoints> resolvedEndpoints,
            Optional<ReviewEndpoint> before,
            Optional<ReviewEndpoint> after,
            Optional<GitComparisonId> comparisonId,
            Optional<Instant> publishedAt,
            Optional<String> failureCategory) {
        ModelValidation.require(resolvedEndpoints.isPresent() && after.isPresent() && comparisonId.isPresent() && publishedAt.isPresent(),
                "ready review requires resolved endpoints, after, comparison, and publication time");
        ModelValidation.require(failureCategory.isEmpty(), "ready review must not have a failure category");
        ResolvedReviewEndpoints resolved = resolvedEndpoints.orElseThrow();
        ModelValidation.require(resolved.afterRevision().equals(selection.afterRevision()),
                "resolved after revision must match the selection");
        ModelValidation.require((selection.kind() == ReviewComparisonType.RANGE) == (resolved.baselineRule() == ReviewBaselineRule.DIRECT_RANGE),
                "resolved baseline rule must match selection kind");
        if (selection.kind() == ReviewComparisonType.RANGE) {
            ModelValidation.require(resolved.beforeRevision().equals(selection.beforeRevision()),
                    "range before revision must match the selection");
        }
        ModelValidation.require(before.isPresent() == resolved.beforeRevision().isPresent(),
                "review before membership must match the resolved endpoint");
        before.ifPresent(endpoint -> {
            validateEndpoint(repositoryId, endpoint);
            ModelValidation.require(endpoint.generation().selected().revision().equals(resolved.beforeRevision().orElseThrow()),
                    "review before revision must match the resolved revision");
        });
        ReviewEndpoint afterEndpoint = after.orElseThrow();
        validateEndpoint(repositoryId, afterEndpoint);
        ModelValidation.require(afterEndpoint.generation().selected().revision().equals(resolved.afterRevision()),
                "review after revision must match the resolved revision");
        before.ifPresent(endpoint -> requireDistinctSnapshots(endpoint, afterEndpoint));
    }

    private static void requirePreparingOrFailed(
            Optional<ReviewEndpoint> before,
            Optional<ReviewEndpoint> after,
            Optional<GitComparisonId> comparisonId,
            Optional<Instant> publishedAt,
            Optional<String> failureCategory,
            boolean failed) {
        ModelValidation.require(before.isEmpty() && after.isEmpty() && comparisonId.isEmpty() && publishedAt.isEmpty(),
                "only ready reviews may contain review membership");
        if (failed) {
            ModelValidation.require(failureCategory.isPresent(), "failed review requires a failure category");
        } else {
            ModelValidation.require(failureCategory.isEmpty(), "preparing review must not have a failure category");
        }
    }

    private static void validateEndpoint(RepositoryId repositoryId, ReviewEndpoint endpoint) {
        ModelValidation.require(endpoint.generation().selected().repositoryId().equals(repositoryId),
                "review endpoint repository must match review repository");
    }

    private static void requireDistinctSnapshots(ReviewEndpoint a, ReviewEndpoint b) {
        ModelValidation.require(!a.snapshotId().equals(b.snapshotId()),
                "review sides must retain distinct immutable Git snapshots");
    }
}
