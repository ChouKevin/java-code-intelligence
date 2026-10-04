package com.java.semantic.indexer.job;

import com.java.semantic.indexer.source.SourcePublicationStore;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** One dispatcher thread, started only after startup recovery completes. */
@Component
public final class IndexJobDispatcher implements ApplicationListener<ApplicationReadyEvent>, SmartLifecycle {
    private final FileSourceJobStore jobs;
    private final IndexJobExecutor executor;
    private final SourcePublicationStore publications;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "source-preparation-dispatcher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public IndexJobDispatcher(FileSourceJobStore jobs, IndexJobExecutor executor, SourcePublicationStore publications) {
        this.jobs = jobs;
        this.executor = executor;
        this.publications = publications;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        start();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            worker.scheduleWithFixedDelay(this::dispatchScheduled, 0, 1, TimeUnit.SECONDS);
        }
    }

    public void dispatchOnce() {
        jobs.pendingStatus().ifPresent(publications::updatePreparation);
        jobs.claimNext().ifPresent(job -> {
            try {
                publications.updatePreparation(job);
            } catch (RuntimeException exception) {
                SourcePreparationJob failed = jobs.fail(new com.java.semantic.model.repository.RepositoryId(job.repositoryId()),
                        new IndexJobId(job.jobId()), "PREPARATION_FAILED");
                try {
                    publications.updatePreparation(failed);
                } catch (RuntimeException publicationFailure) {
                    exception.addSuppressed(publicationFailure);
                }
                throw exception;
            }
            executor.execute(job);
        });
    }

    private void dispatchScheduled() {
        try {
            dispatchOnce();
        } catch (RuntimeException exception) {
            // The next poll may handle transient storage unavailability; never disclose paths or credentials.
            org.slf4j.LoggerFactory.getLogger(IndexJobDispatcher.class).error("Source dispatcher unavailable");
        }
    }

    @Override
    public void stop() {
        running.set(false);
        worker.shutdown();
        boolean interrupted = false;
        while (!worker.isTerminated()) {
            try {
                if (!worker.awaitTermination(30, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                worker.shutdownNow();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }
}
