package com.java.semantic.model.git;

import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.support.ModelValidation;
import java.util.Objects;
import java.util.Optional;

/** Binds Git evidence to either a standalone job or one review manifest. */
public record GitEvidenceOwnership(GitPublicationScope scope, Optional<ReviewId> reviewId) {

    public GitEvidenceOwnership {
        scope = Objects.requireNonNull(scope, "Git evidence publication scope is required");
        reviewId = Objects.requireNonNull(reviewId, "Git evidence review id is required");
        switch (scope) {
            case STANDALONE -> ModelValidation.require(reviewId.isEmpty(),
                    "standalone Git evidence must not have a review id");
            case REVIEW -> ModelValidation.require(reviewId.isPresent(),
                    "review Git evidence requires a review id");
        }
    }

    public static GitEvidenceOwnership standalone() {
        return new GitEvidenceOwnership(GitPublicationScope.STANDALONE, Optional.empty());
    }
}
