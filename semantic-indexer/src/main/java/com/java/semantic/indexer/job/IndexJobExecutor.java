package com.java.semantic.indexer.job;

import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.RollbackGenerationCommand;
import java.util.Objects;
import java.util.Optional;

/** The sole normal owner of terminal state transitions for an already running job. */
public final class IndexJobExecutor {
    private final IndexJobStore jobs;
    private final RepositoryBuildRunner buildRunner;
    private final PublicationPort publication;
    private final Optional<ResetJobHandler> resetHandler;

    public IndexJobExecutor(IndexJobStore jobs, RepositoryBuildRunner buildRunner, PublicationPort publication,
                            Optional<ResetJobHandler> resetHandler) {
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.buildRunner = Objects.requireNonNull(buildRunner, "build runner is required");
        this.publication = Objects.requireNonNull(publication, "publication is required");
        this.resetHandler = Objects.requireNonNull(resetHandler, "reset handler is required");
    }

    public void execute(IndexJob job) {
        requireActiveRunning(job);
        try {
            executeOperation(job);
            complete(job);
        } catch (CompletionTransitionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (!reconcileCommitted(job)) {
                jobs.fail(job.id(), category(exception));
            }
            throw exception;
        }
    }

    private void executeOperation(IndexJob job) {
        switch (job.operation()) {
            case BUILD -> buildRunner.run(job);
            case ROLLBACK -> rollback(job);
            case RESET -> resetHandler.orElseThrow(() -> new IllegalStateException("RESET handler is not registered")).reset(job);
            case NO_WORK -> throw new IllegalArgumentException("NO_WORK is not runnable");
        }
    }

    private void complete(IndexJob job) {
        if (jobs.complete(job.id())) {
            return;
        }
        if (reconcileCommitted(job)) {
            return;
        }
        jobs.fail(job.id(), IndexFailureCategory.WORKER_INTERRUPTED);
        throw new CompletionTransitionException(job.id());
    }

    private void rollback(IndexJob job) {
        RollbackGenerationCommand command = jobs.rollbackCommand(job).orElseThrow(() -> new IllegalStateException("rollback precondition failed"));
        publication.rollback(command);
    }

    private boolean reconcileCommitted(IndexJob job) {
        return jobs.reconcileCommitted(job.repositoryId())
                .filter(reconciled -> reconciled.id().equals(job.id())
                        && reconciled.target().equals(job.target())
                        && reconciled.phase() == IndexJobPhase.COMPLETE
                        && !reconciled.active())
                .isPresent();
    }

    private static void requireActiveRunning(IndexJob job) {
        IndexJob requiredJob = Objects.requireNonNull(job, "job is required");
        if (!requiredJob.active() || requiredJob.phase() != IndexJobPhase.RUNNING) {
            throw new IllegalArgumentException("executor requires an active RUNNING job");
        }
        if (requiredJob.operation() == IndexJobOperation.NO_WORK) {
            throw new IllegalArgumentException("NO_WORK is not runnable");
        }
    }

    private static IndexFailureCategory category(RuntimeException exception) {
        if (exception instanceof com.java.semantic.indexer.store.PublicationConflictException) {
            return IndexFailureCategory.PUBLICATION_CONFLICT;
        }
        if (exception instanceof com.java.semantic.repository.application.RepositoryMutationException) {
            return IndexFailureCategory.SOURCE_UNAVAILABLE;
        }
        if (exception instanceof IndexSchemaRebuildRequiredException
                || exception instanceof com.java.semantic.indexer.store.IndexSchemaMaintenanceRequiredException) {
            return IndexFailureCategory.SCHEMA_REBUILD_REQUIRED;
        }
        if (exception instanceof IndexBuildService.GenerationValidationException) {
            return IndexFailureCategory.VALIDATION_FAILED;
        }
        return IndexFailureCategory.WORKER_INTERRUPTED;
    }

    @FunctionalInterface
    public interface ResetJobHandler {
        void reset(IndexJob job);
    }

    private static final class CompletionTransitionException extends IllegalStateException {
        private CompletionTransitionException(IndexJobId jobId) {
            super("job completion transition failed: " + jobId.value());
        }
    }
}
