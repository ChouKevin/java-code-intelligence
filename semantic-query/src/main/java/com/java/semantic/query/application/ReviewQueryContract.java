package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.model.support.ModelValidation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Transport-neutral, immutable requests and safe results for review-scoped semantic evidence. */
public final class ReviewQueryContract {

    public record ReviewRequest(String repositoryId, String reviewId) {
        public ReviewRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
        }
    }

    public record ReviewSearchCodeRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                          String query, Set<CodeFactKind> kinds, Optional<String> packagePrefix,
                                          int offset, int limit) {
        public ReviewSearchCodeRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            new SemanticQueryContract.SearchCodeRequest(repositoryId, revision, query, kinds, packagePrefix, offset, limit);
            query = ModelValidation.requiredText(query, "query");
            kinds = immutableKinds(kinds);
            packagePrefix = requiredOptional(packagePrefix, "package prefix");
        }
    }

    public record ReviewFactSourceRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                          String factId, int contextLines) {
        public ReviewFactSourceRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            factId = new CodeFactId(factId).value();
            new SemanticQueryContract.FactSourceRequest(repositoryId, revision, factId, contextLines);
        }
    }

    public record ReviewEntryPointRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                          Set<EntryPointKind> kinds, int offset, int limit) {
        public ReviewEntryPointRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            new SemanticQueryContract.EntryPointRequest(repositoryId, revision, kinds, offset, limit);
            kinds = immutableKinds(kinds);
        }
    }

    public record ReviewApiRouteRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                        SemanticQueryContract.HttpMethod httpMethod, String path, int offset, int limit) {
        public ReviewApiRouteRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            new SemanticQueryContract.ApiRouteRequest(repositoryId, revision, httpMethod, path, offset, limit);
            httpMethod = Objects.requireNonNull(httpMethod, "HTTP method is required");
            path = ModelValidation.requiredText(path, "path");
        }
    }

    public record ReviewEventListenerRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                             String eventType, int offset, int limit) {
        public ReviewEventListenerRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            new SemanticQueryContract.EventListenerRequest(repositoryId, revision, eventType, offset, limit);
            eventType = ModelValidation.requiredText(eventType, "event type");
        }
    }

    public record ReviewTypeMemberRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                          String typeFactId, Set<CodeFactKind> kinds, int offset, int limit) {
        public ReviewTypeMemberRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            typeFactId = new CodeFactId(typeFactId).value();
            new SemanticQueryContract.TypeMemberRequest(repositoryId, revision, typeFactId, kinds, offset, limit);
            kinds = immutableKinds(kinds);
        }
    }

    public record ReviewRelationRequest(String repositoryId, String reviewId, ReviewSide side, String revision,
                                        String factId, int offset, int limit) {
        public ReviewRelationRequest {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            factId = new CodeFactId(factId).value();
            new SemanticQueryContract.RelationRequest(repositoryId, revision, factId, offset, limit);
        }
    }

    public record ReviewContext(String repositoryId, String reviewId, ReviewSide side, String revision,
                                String generationId, ReviewCoverage coverage) {
        public ReviewContext {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            side = Objects.requireNonNull(side, "review side is required");
            revision = new RepositoryRevision(revision).value();
            generationId = ModelValidation.requiredText(generationId, "generation id");
            coverage = Objects.requireNonNull(coverage, "review coverage is required");
        }
    }

    public record ReviewCoverage(SemanticQueryContract.SourceCoverage sourceCoverage,
                                 List<String> semanticLimitations) {
        public ReviewCoverage {
            sourceCoverage = Objects.requireNonNull(sourceCoverage, "source coverage is required");
            semanticLimitations = List.copyOf(Objects.requireNonNull(semanticLimitations, "semantic limitations are required"));
        }
    }

    public record ReviewResult<T>(ReviewContext context, T result) {
        public ReviewResult {
            context = Objects.requireNonNull(context, "review context is required");
            result = Objects.requireNonNull(result, "review result is required");
        }
    }


    public record ReviewEndpointDetails(String revision, String generationId, String manifestDigest,
                                        String analysisFingerprint, String snapshotId, ReviewCoverage coverage) {
        public ReviewEndpointDetails {
            revision = new RepositoryRevision(revision).value();
            generationId = ModelValidation.requiredText(generationId, "generation id");
            manifestDigest = ModelValidation.sha256(manifestDigest, "manifest digest");
            analysisFingerprint = ModelValidation.sha256(analysisFingerprint, "analysis fingerprint");
            snapshotId = ModelValidation.requiredText(snapshotId, "snapshot id");
            coverage = Objects.requireNonNull(coverage, "review coverage is required");
        }
    }

    public record ReviewBeforeDetails(ReviewBaselineRule kind, Optional<ReviewEndpointDetails> endpoint) {
        public ReviewBeforeDetails {
            kind = Objects.requireNonNull(kind, "review before kind is required");
            endpoint = Objects.requireNonNull(endpoint, "review before endpoint is required");
            if (endpoint.isEmpty() != (kind == ReviewBaselineRule.EMPTY_TREE)) {
                throw new IllegalArgumentException("empty tree has no semantic before endpoint");
            }
        }
    }

    public record ReviewDetails(String repositoryId, String reviewId, ReviewSelection selection,
                                ResolvedReviewEndpoints resolvedEndpoints, ReviewBeforeDetails before,
                                ReviewEndpointDetails after, String comparisonId, Instant publishedAt) {
        public ReviewDetails {
            repositoryId = new RepositoryId(repositoryId).value();
            reviewId = new ReviewId(reviewId).value();
            selection = Objects.requireNonNull(selection, "review selection is required");
            resolvedEndpoints = Objects.requireNonNull(resolvedEndpoints, "resolved review endpoints are required");
            before = Objects.requireNonNull(before, "review before details are required");
            after = Objects.requireNonNull(after, "review after details are required");
            comparisonId = ModelValidation.requiredText(comparisonId, "comparison id");
            publishedAt = Objects.requireNonNull(publishedAt, "review publication time is required");
        }
    }

    private ReviewQueryContract() {
    }

    private static <T> Set<T> immutableKinds(Set<T> kinds) {
        Set<T> requiredKinds = Objects.requireNonNull(kinds, "kinds are required");
        if (requiredKinds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("kinds must not contain null values");
        }
        return Set.copyOf(requiredKinds);
    }

    private static Optional<String> requiredOptional(Optional<String> value, String field) {
        return Objects.requireNonNull(value, field + " is required")
                .map(item -> ModelValidation.requiredText(item, field));
    }
}
