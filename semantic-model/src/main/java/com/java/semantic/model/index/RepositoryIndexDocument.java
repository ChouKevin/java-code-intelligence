package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Latest-only repository coordinator and current-generation pointer. */
public record RepositoryIndexDocument(
        RepositoryId repositoryId,
        RepositoryFence fence,
        Optional<String> activeJobId,
        Optional<String> activeWorkerId,
        Optional<GenerationId> activeGenerationId,
        Optional<Instant> claimExpiresAt,
        Optional<CurrentPublication> current,
        Optional<CurrentPublication> previous) {

    public RepositoryIndexDocument {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        fence = Objects.requireNonNull(fence, "repository fence is required");
        activeJobId = Objects.requireNonNull(activeJobId, "active job id is required");
        activeWorkerId = Objects.requireNonNull(activeWorkerId, "active worker id is required");
        activeGenerationId = Objects.requireNonNull(activeGenerationId, "active generation id is required");
        claimExpiresAt = Objects.requireNonNull(claimExpiresAt, "claim expiry is required");
        current = Objects.requireNonNull(current, "current publication is required");
        previous = Objects.requireNonNull(previous, "previous publication is required");
    }

    public record CurrentPublication(
            com.java.semantic.model.repository.RepositoryRevision revision,
            GenerationId generationId,
            ManifestDigest manifestDigest,
            String committedJobId,
            Instant publishedAt) {

        public CurrentPublication {
            revision = Objects.requireNonNull(revision, "revision is required");
            generationId = Objects.requireNonNull(generationId, "generation id is required");
            manifestDigest = Objects.requireNonNull(manifestDigest, "manifest digest is required");
            committedJobId = Objects.requireNonNull(committedJobId, "committed job id is required");
            publishedAt = Objects.requireNonNull(publishedAt, "publication time is required");
        }
    }
}
