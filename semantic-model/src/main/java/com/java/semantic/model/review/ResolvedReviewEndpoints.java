package com.java.semantic.model.review;

import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Objects;
import java.util.Optional;

/** Worker-resolved fixed commits; absent before is the real empty Git tree. */
public record ResolvedReviewEndpoints(Optional<RepositoryRevision> beforeRevision,
                                      RepositoryRevision afterRevision, ReviewBaselineRule baselineRule) {
    public ResolvedReviewEndpoints {
        beforeRevision = Objects.requireNonNull(beforeRevision, "resolved before revision is required");
        afterRevision = Objects.requireNonNull(afterRevision, "resolved after revision is required");
        baselineRule = Objects.requireNonNull(baselineRule, "review baseline rule is required");
        if (beforeRevision.isEmpty() != (baselineRule == ReviewBaselineRule.EMPTY_TREE)) {
            throw new IllegalArgumentException("absent before revision requires EMPTY_TREE and vice versa");
        }
    }
}
