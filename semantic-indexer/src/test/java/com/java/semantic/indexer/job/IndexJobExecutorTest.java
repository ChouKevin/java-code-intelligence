package com.java.semantic.indexer.job;

import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.build.IndexBuildService;
import com.java.semantic.indexer.store.IndexSchemaMaintenanceRequiredException;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.indexer.store.PublicationConflictException;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.repository.application.RepositoryMutationException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexJobExecutorTest {
    @Test
    void runs_an_already_running_build_and_completes_once_without_claiming() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        IndexJob job = runningJob(IndexJobOperation.BUILD);
        when(jobs.complete(job.id())).thenReturn(true);

        executor(jobs, runner).execute(job);

        verify(runner).run(job);
        verify(jobs).complete(job.id());
        verify(jobs, never()).startNextAccepted();
    }

    @Test
    void publishes_the_exact_rollback_command_and_completes() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        PublicationPort publication = mock(PublicationPort.class);
        IndexJob job = runningJob(IndexJobOperation.ROLLBACK);
        RollbackGenerationCommand command = mock(RollbackGenerationCommand.class);
        when(jobs.rollbackCommand(job)).thenReturn(Optional.of(command));
        when(jobs.complete(job.id())).thenReturn(true);

        new IndexJobExecutor(jobs, runner, publication, Optional.empty()).execute(job);

        verify(publication).rollback(command);
        verify(jobs).complete(job.id());
        verify(runner, never()).run(any());
    }

    @Test
    void reconciled_committed_build_failure_is_not_overwritten() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        IndexJob job = runningJob(IndexJobOperation.BUILD);
        RuntimeException failure = new RuntimeException("worker failed");
        doThrow(failure).when(runner).run(job);
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.of(completed(job)));

        assertThatThrownBy(() -> executor(jobs, runner).execute(job)).isSameAs(failure);

        verify(jobs, never()).fail(any(), any());
    }

    @ParameterizedTest
    @MethodSource("uncommittedBuildFailures")
    void uncommitted_build_failures_fail_once_with_their_public_category(
            RuntimeException failure,
            IndexFailureCategory category) {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        IndexJob job = runningJob(IndexJobOperation.BUILD);
        doThrow(failure).when(runner).run(job);
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor(jobs, runner).execute(job)).isSameAs(failure);

        verify(jobs).fail(job.id(), category);
    }

    @Test
    void late_completion_race_reconciles_the_committed_target_without_failing() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        IndexJob job = runningJob(IndexJobOperation.BUILD);
        when(jobs.complete(job.id())).thenReturn(false);
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.of(completed(job)));

        executor(jobs, runner).execute(job);

        verify(jobs, never()).fail(any(), any());
    }

    @Test
    void rejects_non_running_or_inactive_jobs_before_business_work() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        IndexJob running = runningJob(IndexJobOperation.BUILD);
        IndexJob accepted = new IndexJob(running.id(), running.repositoryId(), running.target(), IndexJobPhase.ACCEPTED, true,
                Optional.empty(), false, IndexJobOperation.BUILD);
        IndexJob completed = completed(running);
        IndexJob noWork = new IndexJob(new IndexJobId("no-work"), RepositoryId.of("orders"), Optional.empty(),
                IndexJobPhase.COMPLETE, false, Optional.empty(), false, IndexJobOperation.NO_WORK);

        assertThatThrownBy(() -> executor(jobs, runner).execute(accepted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active RUNNING");
        assertThatThrownBy(() -> executor(jobs, runner).execute(completed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active RUNNING");
        assertThatThrownBy(() -> executor(jobs, runner).execute(noWork))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active RUNNING");

        verify(runner, never()).run(any());
        verify(jobs, never()).complete(any());
        verify(jobs, never()).fail(any(), any());
    }

    @Test
    void reset_without_a_registered_handler_fails_through_the_normal_path() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        IndexJob job = runningResetJob();
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor(jobs, runner).execute(job))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESET handler");

        verify(jobs).fail(job.id(), IndexFailureCategory.WORKER_INTERRUPTED);
    }

    @Test
    void metadata_cannot_complete_without_a_fully_published_result() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        GitEvidenceJobHandler handler = mock(GitEvidenceJobHandler.class);
        IndexJob job = runningJob(IndexJobOperation.GIT_METADATA);
        when(jobs.gitEvidenceReady(job)).thenReturn(false);

        assertThatThrownBy(() -> new IndexJobExecutor(jobs, runner, mock(PublicationPort.class), Optional.empty(), Optional.of(handler)).execute(job))
                .isInstanceOf(IllegalStateException.class);
        verify(jobs, never()).complete(job.id());
        verify(jobs).fail(job.id(), IndexFailureCategory.WORKER_INTERRUPTED);
    }

    @Test
    void failed_metadata_handler_never_reports_a_false_complete_terminal_state() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        GitEvidenceJobHandler handler = mock(GitEvidenceJobHandler.class);
        IndexJob job = runningJob(IndexJobOperation.GIT_METADATA);
        RuntimeException failure = new RuntimeException("comparison preparation failed");
        doThrow(failure).when(handler).prepare(job);
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new IndexJobExecutor(jobs, runner, mock(PublicationPort.class), Optional.empty(), Optional.of(handler)).execute(job))
                .isSameAs(failure);

        verify(jobs, never()).complete(job.id());
        verify(jobs).fail(job.id(), IndexFailureCategory.WORKER_INTERRUPTED);
    }

    private static IndexJobExecutor executor(IndexJobStore jobs, RepositoryBuildRunner runner) {
        return new IndexJobExecutor(jobs, runner, mock(PublicationPort.class), Optional.empty());
    }

    private static java.util.stream.Stream<Arguments> uncommittedBuildFailures() {
        return java.util.stream.Stream.of(
                Arguments.of(new RuntimeException("worker failed"), IndexFailureCategory.WORKER_INTERRUPTED),
                Arguments.of(new IndexBuildService.GenerationValidationException("CHECKOUT_CHANGED"),
                        IndexFailureCategory.VALIDATION_FAILED),
                Arguments.of(new IndexSchemaRebuildRequiredException(), IndexFailureCategory.SCHEMA_REBUILD_REQUIRED),
                Arguments.of(new PublicationConflictException(),
                        IndexFailureCategory.PUBLICATION_CONFLICT),
                Arguments.of(new RepositoryMutationException("checkout failed"),
                        IndexFailureCategory.SOURCE_UNAVAILABLE),
                Arguments.of(new IndexSchemaMaintenanceRequiredException("missing index"),
                        IndexFailureCategory.SCHEMA_REBUILD_REQUIRED));
    }

    private static IndexJob runningJob(IndexJobOperation operation) {
        RepositoryRevision revision = new RepositoryRevision("a".repeat(40));
        if (operation == IndexJobOperation.GIT_METADATA) {
            return new IndexJob(new IndexJobId("job-1"), RepositoryId.of("orders"), Optional.empty(), IndexJobPhase.RUNNING, true,
                    Optional.empty(), false, operation, Optional.of(GitEvidenceJob.metadata("main")), Optional.empty(),
                    Optional.of(PreparationRequest.metadata(new PreparationRequestId("8f899830-47bb-4dc7-a9a6-c4ad0c016bb3"), Optional.empty())),
                    Optional.empty());
        }
        return new IndexJob(new IndexJobId("job-1"), RepositoryId.of("orders"), Optional.of(new IndexJobTarget(
                revision, new GenerationId("g-1"), 1L)), IndexJobPhase.RUNNING, true, Optional.empty(), false, operation);
    }

    private static IndexJob runningResetJob() {
        return new IndexJob(new IndexJobId("job-reset"), RepositoryId.of("orders"), Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.RESET);
    }

    private static IndexJob completed(IndexJob job) {
        return new IndexJob(job.id(), job.repositoryId(), job.target(), IndexJobPhase.COMPLETE, false,
                Optional.empty(), job.rebuild(), job.operation());
    }
}
