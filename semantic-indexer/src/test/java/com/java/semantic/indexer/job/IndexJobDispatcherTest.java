package com.java.semantic.indexer.job;

import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexJobDispatcherTest {
    @Test
    void dispatches_one_claimed_job_synchronously_and_allows_the_next_poll_after_failure() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        IndexJobExecutor executor = mock(IndexJobExecutor.class);
        IndexJob first = runningJob("job-1", "orders");
        IndexJob second = runningJob("job-2", "payments");
        RuntimeException failure = new RuntimeException("first job failed");
        doReturn(Optional.of(first)).doReturn(Optional.of(second)).when(jobs).startNextAccepted();
        doThrow(failure).when(executor).execute(first);
        IndexJobDispatcher dispatcher = dispatcher(jobs, executor);
        Logger logger = (Logger) LoggerFactory.getLogger(IndexJobDispatcher.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        try {
            assertThatThrownBy(dispatcher::dispatchOnce).isSameAs(failure);

            assertThat(logs.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("repositoryId=orders", "jobId=job-1", "operation=BUILD");
                assertThat(event.getThrowableProxy()).isNotNull();
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(RuntimeException.class.getName());
            });

            dispatcher.dispatchOnce();

            org.mockito.InOrder order = inOrder(executor);
            order.verify(executor).execute(first);
            order.verify(executor).execute(second);
            verify(jobs, times(2)).startNextAccepted();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            dispatcher.stop();
        }
    }

    @Test
    void shutdown_interrupts_the_active_dispatcher_thread_and_prevents_another_poll() throws Exception {
        IndexJobStore jobs = mock(IndexJobStore.class);
        RepositoryBuildRunner runner = mock(RepositoryBuildRunner.class);
        PublicationPort publication = mock(PublicationPort.class);
        IndexJob job = runningJob("job-1", "orders");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch terminalObserved = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        doReturn(Optional.of(job)).doReturn(Optional.empty()).when(jobs).startNextAccepted();
        when(jobs.reconcileCommitted(job.repositoryId())).thenReturn(Optional.empty());
        when(jobs.fail(job.id(), IndexFailureCategory.WORKER_INTERRUPTED)).thenAnswer(invocation -> {
            failed.countDown();
            return true;
        });
        when(jobs.find(job.id())).thenAnswer(invocation -> {
            terminalObserved.countDown();
            return Optional.empty();
        });
        doAnswer(invocation -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException("dispatcher thread interrupted", exception);
            }
            return null;
        }).when(runner).run(job);
        IndexJobExecutor executor = new IndexJobExecutor(jobs, runner, publication, Optional.empty());
        IndexJobDispatcher dispatcher = dispatcher(jobs, executor);

        try {
            dispatcher.onApplicationEvent(mock(ApplicationReadyEvent.class));

            assertThat(started.await(2L, TimeUnit.SECONDS)).isTrue();
            dispatcher.stop();

            assertThat(failed.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.get()).isTrue();
            assertThat(terminalObserved.await(2L, TimeUnit.SECONDS)).isTrue();
            verify(jobs, times(1)).startNextAccepted();
        } finally {
            dispatcher.stop();
        }
    }

    @Test
    void stop_waits_for_an_in_progress_claim_before_completing_shutdown() throws Exception {
        IndexJobStore jobs = mock(IndexJobStore.class);
        IndexJobExecutor executor = mock(IndexJobExecutor.class);
        CountDownLatch claimStarted = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        CountDownLatch stopRequested = new CountDownLatch(1);
        CountDownLatch stopFinished = new CountDownLatch(1);
        CountDownLatch dispatchFinished = new CountDownLatch(1);
        doAnswer(invocation -> {
            claimStarted.countDown();
            awaitUninterruptibly(releaseClaim);
            return Optional.empty();
        }).when(jobs).startNextAccepted();
        IndexJobDispatcher dispatcher = dispatcher(jobs, executor);
        Thread dispatchThread = new Thread(() -> {
            dispatcher.dispatchOnce();
            dispatchFinished.countDown();
        });
        Thread stopThread = new Thread(() -> {
            stopRequested.countDown();
            dispatcher.stop();
            stopFinished.countDown();
        });

        try {
            dispatchThread.start();
            assertThat(claimStarted.await(2L, TimeUnit.SECONDS)).isTrue();

            stopThread.start();
            assertThat(stopRequested.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(stopFinished.await(200L, TimeUnit.MILLISECONDS)).isFalse();

            releaseClaim.countDown();

            assertThat(dispatchFinished.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(stopFinished.await(2L, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseClaim.countDown();
            dispatcher.stop();
            dispatchThread.join(TimeUnit.SECONDS.toMillis(2L));
            stopThread.join(TimeUnit.SECONDS.toMillis(2L));
        }
    }

    @Test
    void stop_callback_runs_once_only_after_the_active_job_and_terminal_write_finish() throws Exception {
        IndexJobStore jobs = mock(IndexJobStore.class);
        IndexJobExecutor executor = mock(IndexJobExecutor.class);
        IndexJob job = runningJob("job-1", "orders");
        CountDownLatch executionStarted = new CountDownLatch(1);
        CountDownLatch releaseExecution = new CountDownLatch(1);
        CountDownLatch terminalWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseTerminalWrite = new CountDownLatch(1);
        CountDownLatch callbackCalled = new CountDownLatch(1);
        AtomicInteger callbackCalls = new AtomicInteger();
        doReturn(Optional.of(job)).when(jobs).startNextAccepted();
        doAnswer(invocation -> {
            executionStarted.countDown();
            awaitUninterruptibly(releaseExecution);
            return null;
        }).when(executor).execute(job);
        when(jobs.find(job.id())).thenAnswer(invocation -> {
            terminalWriteStarted.countDown();
            awaitUninterruptibly(releaseTerminalWrite);
            return Optional.empty();
        });
        IndexJobDispatcher dispatcher = dispatcher(jobs, executor);

        try {
            dispatcher.onApplicationEvent(mock(ApplicationReadyEvent.class));
            assertThat(executionStarted.await(2L, TimeUnit.SECONDS)).isTrue();

            dispatcher.stop(() -> {
                callbackCalls.incrementAndGet();
                callbackCalled.countDown();
            });

            assertThat(callbackCalled.getCount()).isEqualTo(1L);
            releaseExecution.countDown();
            assertThat(terminalWriteStarted.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(callbackCalled.getCount()).isEqualTo(1L);
            releaseTerminalWrite.countDown();

            assertThat(callbackCalled.await(2L, TimeUnit.SECONDS)).isTrue();
            assertThat(callbackCalls).hasValue(1);
        } finally {
            releaseExecution.countDown();
            releaseTerminalWrite.countDown();
            dispatcher.stop();
        }
    }

    @Test
    void stop_callback_before_start_completes_once_without_failure() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        IndexJobExecutor executor = mock(IndexJobExecutor.class);
        AtomicInteger callbackCalls = new AtomicInteger();
        IndexJobDispatcher dispatcher = dispatcher(jobs, executor);

        dispatcher.stop(callbackCalls::incrementAndGet);

        assertThat(callbackCalls).hasValue(1);
    }

    @Test
    void stop_callback_registered_during_termination_also_completes_once() {
        IndexJobStore jobs = mock(IndexJobStore.class);
        IndexJobExecutor executor = mock(IndexJobExecutor.class);
        AtomicInteger firstCallbackCalls = new AtomicInteger();
        AtomicInteger secondCallbackCalls = new AtomicInteger();
        IndexJobDispatcher dispatcher = dispatcher(jobs, executor);

        dispatcher.stop(() -> {
            firstCallbackCalls.incrementAndGet();
            dispatcher.stop(secondCallbackCalls::incrementAndGet);
        });

        assertThat(firstCallbackCalls).hasValue(1);
        assertThat(secondCallbackCalls).hasValue(1);
    }

    private static IndexJobDispatcher dispatcher(IndexJobStore jobs, IndexJobExecutor executor) {
        return new IndexJobDispatcher(jobs, executor, new IndexJobProperties(Duration.ofSeconds(1L)));
    }

    private static IndexJob runningJob(String jobId, String repositoryId) {
        return new IndexJob(new IndexJobId(jobId), RepositoryId.of(repositoryId), Optional.of(new IndexJobTarget(
                new RepositoryRevision("a".repeat(40)), new GenerationId("g-" + jobId), 1L)), IndexJobPhase.RUNNING,
                true, Optional.empty(), false, IndexJobOperation.BUILD);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
