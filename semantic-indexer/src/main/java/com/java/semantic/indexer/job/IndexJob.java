package com.java.semantic.indexer.job;

import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RepositoryFence;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Public job model, intentionally separate from the Mongo document. */
public record IndexJob(
        IndexJobId id,
        RepositoryId repositoryId,
        RepositoryRevision revision,
        GenerationId generationId,
        long generation,
        IndexJobPhase phase,
        boolean active,
        Optional<String> workerId,
        Optional<RepositoryFence> fence,
        Optional<Instant> claimUntil,
        Optional<IndexFailureCategory> failureCategory,
        boolean rebuild,
        IndexJobOperation operation) {
    public IndexJob {
        id = Objects.requireNonNull(id, "job id is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        generationId = Objects.requireNonNull(generationId, "generation id is required");
        phase = Objects.requireNonNull(phase, "phase is required");
        workerId = Objects.requireNonNull(workerId, "worker id is required");
        fence = Objects.requireNonNull(fence, "fence is required");
        claimUntil = Objects.requireNonNull(claimUntil, "claim until is required");
        failureCategory = Objects.requireNonNull(failureCategory, "failure category is required");
        operation = Objects.requireNonNull(operation, "operation is required");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive");
        }
    }

    public IndexJob(IndexJobId id, RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                    long generation, IndexJobPhase phase, boolean active, Optional<String> workerId,
                    Optional<RepositoryFence> fence, Optional<Instant> claimUntil,
                    Optional<IndexFailureCategory> failureCategory) {
        this(id, repositoryId, revision, generationId, generation, phase, active, workerId, fence, claimUntil,
                failureCategory, false, IndexJobOperation.BUILD);
    }

    public IndexJob(IndexJobId id, RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                    long generation, IndexJobPhase phase, boolean active, Optional<String> workerId,
                    Optional<RepositoryFence> fence, Optional<Instant> claimUntil,
                    Optional<IndexFailureCategory> failureCategory, IndexJobOperation operation) {
        this(id, repositoryId, revision, generationId, generation, phase, active, workerId, fence, claimUntil,
                failureCategory, false, operation);
    }

    public IndexJob(IndexJobId id, RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                    long generation, IndexJobPhase phase, boolean active, Optional<String> workerId,
                    Optional<RepositoryFence> fence, Optional<Instant> claimUntil,
                    Optional<IndexFailureCategory> failureCategory, boolean rebuild) {
        this(id, repositoryId, revision, generationId, generation, phase, active, workerId, fence, claimUntil,
                failureCategory, rebuild, IndexJobOperation.BUILD);
    }
}
