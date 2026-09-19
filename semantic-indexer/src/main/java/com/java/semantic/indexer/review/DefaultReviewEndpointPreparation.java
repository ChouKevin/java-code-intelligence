package com.java.semantic.indexer.review;

import com.java.semantic.indexer.analysis.AnalysisReuseVerifier;
import com.java.semantic.indexer.analysis.AnalysisTarget;
import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.repository.ExactRepositoryCheckout;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.repository.domain.RepositorySnapshot;
import java.util.List;
import java.util.Objects;

/** Production adapter which materializes the exact target before deciding endpoint reuse. */
public final class DefaultReviewEndpointPreparation implements ReviewEndpointPreparationPort {
    private final ExactRepositoryCheckout checkout;
    private final RepositoryBuildRunner builds;
    private final AnalysisReuseVerifier reuse;

    public DefaultReviewEndpointPreparation(ExactRepositoryCheckout checkout, RepositoryBuildRunner builds,
                                            AnalysisReuseVerifier reuse) {
        this.checkout = Objects.requireNonNull(checkout, "checkout is required");
        this.builds = Objects.requireNonNull(builds, "build runner is required");
        this.reuse = Objects.requireNonNull(reuse, "reuse verifier is required");
    }

    @Override
    public SealedGeneration prepare(IndexJob job, ReviewSide side, List<SealedGeneration> reuseCandidates) {
        IndexJob requiredJob = Objects.requireNonNull(job, "review job is required");
        ReviewSide requiredSide = Objects.requireNonNull(side, "review side is required");
        List<SealedGeneration> candidates = List.copyOf(Objects.requireNonNull(reuseCandidates, "reuse candidates are required"));
        IndexBuildService.CheckedOutRepository checkedOut = checkout.checkout(requiredJob);
        AnalysisTarget target = new AnalysisTarget(new RepositorySnapshot(requiredJob.repositoryId(), checkedOut.root(), checkedOut.revision()),
                requiredJob.id().value(), requiredSide.name());
        for (SealedGeneration candidate : candidates) {
            if (reuse.matches(candidate, target)) {
                return candidate;
            }
        }
        return builds.seal(requiredJob);
    }
}
