package com.java.semantic.indexer.review;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.review.ReviewSide;
import java.util.List;

/** Prepares one immutable review endpoint after its reserved target has been activated. */
public interface ReviewEndpointPreparationPort {
    SealedGeneration prepare(IndexJob job, ReviewSide side, List<SealedGeneration> reuseCandidates);
}
