package com.java.semantic.indexer.job;

import com.java.semantic.model.repository.RepositoryId;

import java.util.Objects;
import java.util.Optional;

/** Public job model, intentionally separate from the Mongo document. */
public record IndexJob(
        IndexJobId id,
        RepositoryId repositoryId,
        Optional<IndexJobTarget> target,
        IndexJobPhase phase,
        boolean active,
        Optional<IndexFailureCategory> failureCategory,
        boolean rebuild,
        IndexJobOperation operation,
        Optional<GitEvidenceJob> gitEvidence) {
    public IndexJob {
        id = Objects.requireNonNull(id, "job id is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        target = Objects.requireNonNull(target, "target is required");
        phase = Objects.requireNonNull(phase, "phase is required");
        failureCategory = Objects.requireNonNull(failureCategory, "failure category is required");
        operation = Objects.requireNonNull(operation, "operation is required");
        gitEvidence = Objects.requireNonNull(gitEvidence, "git evidence is required");
        if ((operation == IndexJobOperation.BUILD || operation == IndexJobOperation.ROLLBACK) && target.isEmpty()) {
            throw new IllegalArgumentException(operation + " requires a target");
        }
        if (operation == IndexJobOperation.RESET && target.isPresent()) {
            throw new IllegalArgumentException("RESET forbids a target");
        }
        if (operation == IndexJobOperation.NO_WORK && (active || phase != IndexJobPhase.COMPLETE)) {
            throw new IllegalArgumentException("NO_WORK must be inactive and complete");
        }
        if ((operation == IndexJobOperation.GIT_REFS || operation == IndexJobOperation.GIT_HISTORY) && (target.isPresent() || gitEvidence.isEmpty())) {
            throw new IllegalArgumentException("Git evidence work requires a Git payload and no semantic target");
        }
    }

    public IndexJob(IndexJobId id, RepositoryId repositoryId, Optional<IndexJobTarget> target, IndexJobPhase phase,
                    boolean active, Optional<IndexFailureCategory> failureCategory, boolean rebuild, IndexJobOperation operation) {
        this(id, repositoryId, target, phase, active, failureCategory, rebuild, operation, Optional.empty());
    }
}
