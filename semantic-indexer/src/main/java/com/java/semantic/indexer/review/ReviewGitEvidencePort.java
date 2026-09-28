package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.model.review.ResolvedReviewEndpoints;

/** Publishes the one review-owned Git comparison after both semantic endpoints are recorded. */
public interface ReviewGitEvidencePort {
    ResolvedReviewEndpoints resolve(IndexJob reviewJob);
    void prepare(IndexJob reviewJob);
}
