package com.java.semantic.query.application;

import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.application.SelectedGenerationGuard.SourceContext;
import com.java.semantic.query.application.SemanticQueryContract.ComparisonContext;
import com.java.semantic.query.application.SemanticQueryContract.ComparisonEndpoint;
import com.java.semantic.query.application.SemanticQueryContract.ContextKind;
import com.java.semantic.query.application.SemanticQueryContract.EndpointKind;
import com.java.semantic.query.application.SemanticQueryContract.ReadContext;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

/** Admits immutable evidence once; nested readers never consult a current/latest pointer. */
public final class ReadContextSelector {
    public static final ProjectionRequirements SOURCE_ONLY = new ProjectionRequirements(EnumSet.of(ProjectionName.SOURCES));
    public enum Access { SEMANTIC, WHOLE_SOURCE }
    public record AdmittedContext(ReadContext context, SourceContext source) { }
    public record AdmittedComparison(ComparisonContext comparisonContext, ReviewManifestDocument manifest,
            Optional<AdmittedContext> before, AdmittedContext after) { }

    private final CurrentGenerationSelector currents;
    private final ReviewManifestReadService reviews;
    private final SelectedGenerationGuard guard;
    private final ConfiguredReadPolicy policy;

    public ReadContextSelector(CurrentGenerationSelector currents, ReviewManifestReadService reviews,
            SelectedGenerationGuard guard, ConfiguredReadPolicy policy) {
        this.currents = Objects.requireNonNull(currents);
        this.reviews = Objects.requireNonNull(reviews);
        this.guard = Objects.requireNonNull(guard);
        this.policy = Objects.requireNonNull(policy);
    }

    public AdmittedContext select(ReadContext context, ProjectionRequirements requirements, Access access) {
        RepositoryId repository = new RepositoryId(context.repositoryId());
        authorize(repository, access);
        if (context.kind() == ContextKind.CURRENT) {
            return new AdmittedContext(context, currents.selectSourceContext(context.repositoryId(), context.revision(), requirements));
        }
        ReviewManifestDocument manifest = reviews.requireReady(repository, new ReviewId(context.reviewId().orElseThrow()));
        return reviewSide(manifest, context.side().orElseThrow(), context, requirements);
    }

    public AdmittedComparison selectComparison(ComparisonContext context) {
        RepositoryId repository = new RepositoryId(context.repositoryId());
        authorize(repository, Access.WHOLE_SOURCE);
        ReviewManifestDocument manifest = reviews.requireReady(repository, new ReviewId(context.reviewId()));
        if (!comparisonContext(manifest).equals(context)) throw new ReviewContextMismatchException();
        return admitComparison(manifest, SOURCE_ONLY);
    }

    AdmittedComparison admitComparison(ReviewManifestDocument manifest, ProjectionRequirements requirements) {
        Optional<AdmittedContext> before = manifest.before().map(endpoint -> reviewSide(manifest, ReviewSide.BEFORE,
                publicContext(manifest, ReviewSide.BEFORE, endpoint), requirements));
        ReviewEndpoint after = manifest.after().orElseThrow(IndexContractMismatchException::new);
        return new AdmittedComparison(comparisonContext(manifest), manifest, before,
                reviewSide(manifest, ReviewSide.AFTER, publicContext(manifest, ReviewSide.AFTER, after), requirements));
    }

    private AdmittedContext reviewSide(ReviewManifestDocument manifest, ReviewSide side, ReadContext context,
            ProjectionRequirements requirements) {
        ReviewEndpoint endpoint = (side == ReviewSide.BEFORE ? manifest.before() : manifest.after())
                .orElseThrow(ReviewContextMismatchException::new);
        if (!publicContext(manifest, side, endpoint).equals(context)) throw new ReviewContextMismatchException();
        SourceContext source = guard.requireSourceContext(endpoint.generation().selected(), requirements);
        source = guard.requireReviewSnapshot(source, endpoint.snapshotId(), manifest.reviewId(), manifest.ownerJobId());
        return new AdmittedContext(context, source);
    }

    static ComparisonContext comparisonContext(ReviewManifestDocument manifest) {
        ComparisonEndpoint before = manifest.before().map(endpoint -> new ComparisonEndpoint(EndpointKind.REVISION,
                Optional.of(endpoint.generation().selected().revision().value())))
                .orElseGet(() -> new ComparisonEndpoint(EndpointKind.EMPTY_TREE, Optional.empty()));
        ReviewEndpoint after = manifest.after().orElseThrow(IndexContractMismatchException::new);
        return new ComparisonContext(manifest.repositoryId().value(), manifest.reviewId().value(), before,
                new ComparisonEndpoint(EndpointKind.REVISION, Optional.of(after.generation().selected().revision().value())));
    }

    private static ReadContext publicContext(ReviewManifestDocument manifest, ReviewSide side, ReviewEndpoint endpoint) {
        return ReadContext.review(manifest.repositoryId().value(), manifest.reviewId().value(), side,
                endpoint.generation().selected().revision().value());
    }

    private void authorize(RepositoryId repository, Access access) {
        if (!policy.isRepositoryVisible(repository)) throw new RepositoryNotFoundException();
        if (access == Access.WHOLE_SOURCE) policy.requireGitEvidenceVisible(repository);
    }
}
