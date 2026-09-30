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
        Optional<GitEvidenceJob> gitEvidence,
        Optional<ReviewJobPayload> review,
        Optional<PreparationRequest> preparation,
        Optional<String> preparationBranch) {
    public IndexJob {
        id = Objects.requireNonNull(id, "job id is required");
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        target = Objects.requireNonNull(target, "target is required");
        phase = Objects.requireNonNull(phase, "phase is required");
        failureCategory = Objects.requireNonNull(failureCategory, "failure category is required");
        operation = Objects.requireNonNull(operation, "operation is required");
        gitEvidence = Objects.requireNonNull(gitEvidence, "git evidence is required");
        review = Objects.requireNonNull(review, "review payload is required");
        preparation = Objects.requireNonNull(preparation, "preparation request is required");
        preparationBranch = Objects.requireNonNull(preparationBranch, "preparation branch is required");
        if (preparationBranch.filter(String::isBlank).isPresent()) {
            throw new IllegalArgumentException("preparation branch must not be blank");
        }
        if (preparation.isPresent()) {
            PreparationRequest request = preparation.orElseThrow();
            IndexJobOperation expectedOperation = switch (request.operation()) {
                case PREPARE_CODEBASE -> IndexJobOperation.BUILD;
                case PREPARE_REVIEW -> IndexJobOperation.REVIEW;
                case REFRESH_REPOSITORY_METADATA -> IndexJobOperation.GIT_METADATA;
            };
            if (operation != expectedOperation
                    || (request.operation() == PreparationOperation.PREPARE_CODEBASE) != preparationBranch.isPresent()) {
                throw new IllegalArgumentException("preparation identity does not match the execution operation");
            }
            if (request.operation() == PreparationOperation.PREPARE_REVIEW
                    && !request.selection().equals(review.map(ReviewJobPayload::selection))) {
                throw new IllegalArgumentException("review request and execution selection must match");
            }
        } else if (preparationBranch.isPresent()) {
            throw new IllegalArgumentException("a fixed preparation branch requires a preparation request");
        }
        if ((operation == IndexJobOperation.REVIEW || operation == IndexJobOperation.GIT_METADATA) && preparation.isEmpty()) {
            throw new IllegalArgumentException("review and metadata jobs require their original preparation request");
        }
        if (operation != IndexJobOperation.GIT_METADATA && gitEvidence.isPresent()) {
            throw new IllegalArgumentException("only metadata jobs carry a Git metadata payload");
        }
        if ((operation == IndexJobOperation.BUILD || operation == IndexJobOperation.ROLLBACK) && target.isEmpty()) {
            throw new IllegalArgumentException(operation + " requires a target");
        }
        if (operation == IndexJobOperation.RESET && target.isPresent()) {
            throw new IllegalArgumentException("RESET forbids a target");
        }
        if (operation == IndexJobOperation.NO_WORK && (active || phase != IndexJobPhase.COMPLETE)) {
            throw new IllegalArgumentException("NO_WORK must be inactive and complete");
        }
        if (operation == IndexJobOperation.GIT_METADATA
                && (target.isPresent() || gitEvidence.isEmpty() || review.isPresent())) {
            throw new IllegalArgumentException("Git evidence work requires a Git payload and no semantic target");
        }
        if (operation == IndexJobOperation.GIT_METADATA && gitEvidence.flatMap(GitEvidenceJob::branch).isEmpty()) {
            throw new IllegalArgumentException("metadata preparation requires its effective branch");
        }
        if (operation == IndexJobOperation.REVIEW) {
            ReviewJobPayload payload = review.orElseThrow(() -> new IllegalArgumentException("REVIEW requires a review payload"));
            if (gitEvidence.isPresent()) {
                throw new IllegalArgumentException("REVIEW forbids a Git payload");
            }
            switch (payload.stage()) {
                case BUILDING_BEFORE -> requireReviewTarget(target, payload.reservedTargets().orElseThrow().before().orElseThrow());
                case BUILDING_AFTER -> requireReviewTarget(target, payload.reservedTargets().orElseThrow().after());
                default -> {
                    if (target.isPresent()) {
                        throw new IllegalArgumentException("review target is present only while a side is building");
                    }
                }
            }
        } else if (review.isPresent()) {
            throw new IllegalArgumentException(operation + " forbids a review payload");
        }
    }

    public IndexJob(IndexJobId id, RepositoryId repositoryId, Optional<IndexJobTarget> target, IndexJobPhase phase,
                    boolean active, Optional<IndexFailureCategory> failureCategory, boolean rebuild, IndexJobOperation operation,
                    Optional<GitEvidenceJob> gitEvidence) {
        this(id, repositoryId, target, phase, active, failureCategory, rebuild, operation, gitEvidence,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public IndexJob(IndexJobId id, RepositoryId repositoryId, Optional<IndexJobTarget> target, IndexJobPhase phase,
                    boolean active, Optional<IndexFailureCategory> failureCategory, boolean rebuild, IndexJobOperation operation) {
        this(id, repositoryId, target, phase, active, failureCategory, rebuild, operation,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void requireReviewTarget(Optional<IndexJobTarget> target, IndexJobTarget reserved) {
        if (!target.filter(reserved::equals).isPresent()) {
            throw new IllegalArgumentException("active review target must equal its reserved target");
        }
    }
}
