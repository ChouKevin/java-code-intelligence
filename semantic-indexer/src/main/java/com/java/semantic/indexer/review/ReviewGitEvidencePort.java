package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexJob;

/** Publishes the one review-owned Git comparison after both semantic endpoints are recorded. */
public interface ReviewGitEvidencePort {
    void prepare(IndexJob reviewJob);
}
