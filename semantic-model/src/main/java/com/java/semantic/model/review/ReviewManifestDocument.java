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
        ReviewComparisonType comparisonType,
        CapturedReviewBaseline capturedBaseline,
        RepositoryRevision requestedRevision,
        Optional<ReviewEndpoint> a,
        Optional<ReviewEndpoint> b,
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
        comparisonType = Objects.requireNonNull(comparisonType, "review comparison type is required");
        capturedBaseline = Objects.requireNonNull(capturedBaseline, "captured review baseline is required");
        requestedRevision = Objects.requireNonNull(requestedRevision, "requested review revision is required");
        a = Objects.requireNonNull(a, "review A endpoint is required");
        b = Objects.requireNonNull(b, "review B endpoint is required");
        comparisonId = Objects.requireNonNull(comparisonId, "review comparison id is required");
        createdAt = Objects.requireNonNull(createdAt, "review creation time is required");
        publishedAt = Objects.requireNonNull(publishedAt, "review publication time is required");
        failureCategory = Objects.requireNonNull(failureCategory, "review failure category is required")
                .map(value -> ModelValidation.requiredText(value, "review failure category"));

        switch (state) {
            case READY -> requireReady(repositoryId, capturedBaseline, requestedRevision, a, b, comparisonId, publishedAt, failureCategory);
            case PREPARING -> requirePreparingOrFailed(a, b, comparisonId, publishedAt, failureCategory, false);
            case FAILED -> requirePreparingOrFailed(a, b, comparisonId, publishedAt, failureCategory, true);
        }
    }

    private static void requireReady(
            RepositoryId repositoryId,
            CapturedReviewBaseline capturedBaseline,
            RepositoryRevision requestedRevision,
            Optional<ReviewEndpoint> a,
            Optional<ReviewEndpoint> b,
            Optional<GitComparisonId> comparisonId,
            Optional<Instant> publishedAt,
            Optional<String> failureCategory) {
        ModelValidation.require(a.isPresent() && b.isPresent() && comparisonId.isPresent() && publishedAt.isPresent(),
                "ready review requires A, B, comparison, and publication time");
        ModelValidation.require(failureCategory.isEmpty(), "ready review must not have a failure category");
        ReviewEndpoint endpointA = a.orElseThrow();
        ReviewEndpoint endpointB = b.orElseThrow();
        validateEndpoint(repositoryId, endpointA);
        validateEndpoint(repositoryId, endpointB);
        ModelValidation.require(endpointA.generation().selected().revision().equals(capturedBaseline.pointer().revision()),
                "review A revision must match the captured baseline revision");

        ModelValidation.require(endpointB.generation().selected().revision().equals(requestedRevision),
                "review B revision must match the requested review revision");
        requireEqualSidesAgree(endpointA, endpointB);
    }

    private static void requirePreparingOrFailed(
            Optional<ReviewEndpoint> a,
            Optional<ReviewEndpoint> b,
            Optional<GitComparisonId> comparisonId,
            Optional<Instant> publishedAt,
            Optional<String> failureCategory,
            boolean failed) {
        ModelValidation.require(a.isEmpty() && b.isEmpty() && comparisonId.isEmpty() && publishedAt.isEmpty(),
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

    private static void requireEqualSidesAgree(ReviewEndpoint a, ReviewEndpoint b) {
        SealedGeneration generationA = a.generation();
        SealedGeneration generationB = b.generation();
        boolean sameRevision = generationA.selected().revision().equals(generationB.selected().revision());
        boolean sameGeneration = generationA.selected().generationId().equals(generationB.selected().generationId());
        if (sameRevision || sameGeneration) {
            ModelValidation.require(a.equals(b), "equal review sides must have identical generation evidence and snapshots");
        }
    }
}
