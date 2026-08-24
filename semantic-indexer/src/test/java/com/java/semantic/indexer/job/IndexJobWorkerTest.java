package com.java.semantic.indexer.job;

import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishGenerationCommand;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.RepositoryFence;
import com.java.semantic.model.index.RollbackGenerationCommand;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexJobWorkerTest {
    @Test
    void heartbeat_renews_repeatedly_with_claim_lifetime_and_cancels_without_shutting_down_scheduler() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            Runnable heartbeat = invocation.getArgument(0);
            heartbeat.run();
            heartbeat.run();
            return future;
        }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), eq(1000L), eq(1000L),
                eq(java.util.concurrent.TimeUnit.MILLISECONDS));
        IndexJob job = claimedJob(IndexJobOperation.BUILD);
        when(jobs.renew(job, Duration.ofSeconds(20))).thenReturn(true);

        new IndexJobWorker(jobs, publication).runWithHeartbeat(job, Duration.ofSeconds(1), Duration.ofSeconds(20), scheduler,
                guard -> guard.requireHeld());

        verify(jobs, org.mockito.Mockito.times(2)).renew(job, Duration.ofSeconds(20));
        verify(future).cancel(true);
        verify(scheduler, never()).shutdown();
        verify(scheduler, never()).shutdownNow();
    }

    @Test
    void heartbeat_loss_before_work_fails_closed() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        IndexJob job = claimedJob(IndexJobOperation.BUILD);
        when(jobs.renew(job, Duration.ofSeconds(20))).thenReturn(false);
        org.mockito.Mockito.doAnswer(invocation -> {
                    Runnable heartbeat = invocation.getArgument(0);
                    heartbeat.run();
                    return future;
                }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class));

        assertThatThrownBy(() -> new IndexJobWorker(jobs, publication).runWithHeartbeat(job, Duration.ofSeconds(1),
                Duration.ofSeconds(20), scheduler, guard -> guard.requireHeld())).isInstanceOf(ClaimLostException.class);

        Thread.interrupted();
        verify(future).cancel(true);
    }

    @Test
    void heartbeat_loss_during_work_blocks_delayed_write() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(),
                anyLong(), any(java.util.concurrent.TimeUnit.class));
        IndexJob job = claimedJob(IndexJobOperation.BUILD);
        when(jobs.renew(job, Duration.ofSeconds(20))).thenReturn(false);

        assertThatThrownBy(() -> new IndexJobWorker(jobs, publication).runWithHeartbeat(job, Duration.ofSeconds(1),
                Duration.ofSeconds(20), scheduler, guard -> {
                    ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
                    verify(scheduler).scheduleAtFixedRate(heartbeat.capture(), anyLong(), anyLong(), any(java.util.concurrent.TimeUnit.class));
                    heartbeat.getValue().run();
                    guard.requireHeld();
                })).isInstanceOf(ClaimLostException.class);

        Thread.interrupted();
        verify(future).cancel(true);
    }

    @Test
    void heartbeat_runtime_failure_is_claim_loss_and_never_interrupts_after_work_returns() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(future).when(scheduler).scheduleAtFixedRate(heartbeat.capture(), anyLong(), anyLong(),
                any(java.util.concurrent.TimeUnit.class));
        IndexJob job = claimedJob(IndexJobOperation.BUILD);
        when(jobs.renew(job, Duration.ofSeconds(20))).thenThrow(new IllegalStateException("mongo down"));

        new IndexJobWorker(jobs, publication).runWithHeartbeat(job, Duration.ofSeconds(1), Duration.ofSeconds(20), scheduler,
                guard -> guard.requireHeld());
        heartbeat.getValue().run();

        assertThat(Thread.interrupted()).isFalse();
        verify(future).cancel(true);
    }

    @Test
    void heartbeat_is_cancelled_when_work_throws() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(future).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(),
                anyLong(), any(java.util.concurrent.TimeUnit.class));

        assertThatThrownBy(() -> new IndexJobWorker(jobs, publication).runWithHeartbeat(claimedJob(IndexJobOperation.BUILD),
                Duration.ofSeconds(1), Duration.ofSeconds(20), scheduler, guard -> {
                    throw new IllegalStateException("export failed");
                })).isInstanceOf(IllegalStateException.class).hasMessage("export failed");

        verify(future).cancel(true);
    }

    @Test
    void rollback_uses_injected_publication_boundary_then_exact_reconciliation() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        IndexJob job = claimedJob(IndexJobOperation.ROLLBACK);
        PublishedGenerationPointer current = pointer("b", "g-current", "job-current");
        PublishedGenerationPointer rollback = pointer("a", "g-rollback", "job-rollback");
        RollbackGenerationCommand command = new RollbackGenerationCommand(job.repositoryId(), current, rollback, job.id().value(),
                job.workerId().orElseThrow(), job.fence().orElseThrow());
        when(jobs.rollbackCommand(job)).thenReturn(Optional.of(command));
        when(publication.rollback(command)).thenReturn(rollback);
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.of(job));

        assertThat(new IndexJobWorker(jobs, publication).rollback(job)).isTrue();

        verify(publication).rollback(command);
        verify(jobs).reconcileCommitted(job.repositoryId());
    }

    @Test
    void build_publish_persists_intent_before_crossing_the_injected_publication_boundary() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        IndexJob job = claimedJob(IndexJobOperation.BUILD);
        ManifestDigest digest = new ManifestDigest("d".repeat(64));
        IndexPublicationIntent intent = new IndexPublicationIntent(job.id(), IndexJobOperation.BUILD, job.repositoryId(), job.revision(),
                job.generationId(), digest, Optional.empty(), Optional.empty(), Optional.empty());
        when(jobs.prepareBuildPublication(job, digest)).thenReturn(Optional.of(intent));
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.of(job));
        when(publication.publish(any(PublishGenerationCommand.class))).thenReturn(pointer("a", "g-job-1", "job-1"));

        assertThat(new IndexJobWorker(jobs, publication).publishBuild(job, digest)).isTrue();

        ArgumentCaptor<PublishGenerationCommand> command = ArgumentCaptor.forClass(PublishGenerationCommand.class);
        verify(publication).publish(command.capture());
        assertThat(command.getValue().expectedParent()).isEmpty();
        assertThat(command.getValue().sealedManifestDigest()).isEqualTo(digest);
    }

    @Test
    void failure_after_a_lost_revoke_reconciles_instead_of_marking_the_job_failed() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        PublicationPort publication = mock(PublicationPort.class);
        IndexJob job = claimedJob(IndexJobOperation.BUILD);
        when(jobs.revoke(job)).thenReturn(false);
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.of(job));

        assertThat(new IndexJobWorker(jobs, publication).failOrCancel(job)).isFalse();

        verify(jobs).reconcileCommitted(job.repositoryId());
        verify(jobs, never()).failAfterRevocation(job, IndexFailureCategory.WORKER_INTERRUPTED);
    }

    private static IndexJob claimedJob(IndexJobOperation operation) {
        return new IndexJob(new IndexJobId("job-1"), RepositoryId.of("orders"), new RepositoryRevision("a".repeat(40)),
                new GenerationId("g-job-1"), 1, IndexJobPhase.CHECKOUT, true, Optional.of("worker-1"),
                Optional.of(new RepositoryFence(7)), Optional.of(Instant.parse("2026-08-22T00:00:30Z")), Optional.empty(), operation);
    }

    private static PublishedGenerationPointer pointer(String revisionCharacter, String generationId, String jobId) {
        return new PublishedGenerationPointer(new RepositoryRevision(revisionCharacter.repeat(40)), new GenerationId(generationId),
                new ManifestDigest("c".repeat(64)), jobId, Instant.parse("2026-08-22T00:00:00Z"));
    }
}
