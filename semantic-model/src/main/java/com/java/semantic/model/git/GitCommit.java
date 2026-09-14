package com.java.semantic.model.git;

import com.java.semantic.model.repository.RepositoryRevision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** One ordinal in a catalog-pinned topological/time history. */
public record GitCommit(RepositoryRevision revision, List<RepositoryRevision> parents, String subject, Instant committedAt) {
    public GitCommit {
        revision = Objects.requireNonNull(revision, "git commit revision is required");
        parents = List.copyOf(Objects.requireNonNull(parents, "git commit parents are required"));
        subject = Objects.requireNonNull(subject, "git commit subject is required");
        committedAt = Objects.requireNonNull(committedAt, "git commit time is required");
    }
}
