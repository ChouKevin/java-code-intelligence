package com.java.semantic.indexer.job;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;

/** Immutable build input selected when a job enters the durable queue. */
public record IndexJobTarget(RepositoryRevision revision, GenerationId generationId, long generation) {
    public IndexJobTarget {
        revision = Objects.requireNonNull(revision, "revision is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }
}
