package com.java.semantic.indexer.analysis;

import com.java.semantic.repository.domain.RepositorySnapshot;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.Assert;

/** A server-controlled request to prepare one immutable semantic-analysis input. */
public record AnalysisTarget(RepositorySnapshot snapshot, String jobId, String stage) {
    private static final Set<String> STAGES = Set.of("CODEBASE", "A", "B");

    public AnalysisTarget {
        snapshot = Objects.requireNonNull(snapshot, "snapshot is required");
        Assert.hasText(jobId, "jobId is required");
        Assert.isTrue(STAGES.contains(stage), "stage must be CODEBASE, A, or B");
    }
}
