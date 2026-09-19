package com.java.semantic.query.application;

import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import java.util.Objects;

/** Selects one immutable semantic generation from READY review membership. */
public final class ReviewGenerationSelector {
    private final ReviewManifestReadService manifests;
    private final SelectedGenerationGuard guard;

    public ReviewGenerationSelector(ReviewManifestReadService manifests, SelectedGenerationGuard guard) {
        this.manifests = Objects.requireNonNull(manifests, "review manifest reader is required");
        this.guard = Objects.requireNonNull(guard, "selected generation guard is required");
    }

    public ReviewSelection select(RepositoryId repositoryId, ReviewId reviewId, ReviewSide side, RepositoryRevision revision) {
        return select(repositoryId, reviewId, side, revision, SelectedGenerationGuard.ALL_PROJECTIONS);
    }

    /** Selects a READY side without consulting the repository's mutable current pointer. */
    public ReviewSelection select(RepositoryId repositoryId, ReviewId reviewId, ReviewSide side, RepositoryRevision revision,
                                  ProjectionRequirements requirements) {
        RepositoryId requiredRepositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        ReviewId requiredReviewId = Objects.requireNonNull(reviewId, "review id is required");
        ReviewSide requiredSide = Objects.requireNonNull(side, "review side is required");
        RepositoryRevision requiredRevision = Objects.requireNonNull(revision, "revision is required");
        ProjectionRequirements requiredRequirements = Objects.requireNonNull(requirements, "projection requirements are required");
        ReviewManifestDocument manifest = manifests.requireReady(requiredRepositoryId, requiredReviewId);
        ReviewEndpoint endpoint = requiredSide == ReviewSide.A ? manifest.a().orElseThrow(IndexContractMismatchException::new)
                : manifest.b().orElseThrow(IndexContractMismatchException::new);
        SelectedGeneration selected = endpoint.generation().selected();
        if (!requiredRepositoryId.equals(selected.repositoryId()) || !requiredRevision.equals(selected.revision())) {
            throw new ReviewContextMismatchException();
        }
        return new ReviewSelection(manifest, requiredSide, guard.require(selected, requiredRequirements));
    }
}
