package com.java.semantic.indexer.job;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Serially claims and executes durable index jobs after startup recovery has completed. */
@Slf4j
@Component
public final class IndexJobDispatcher implements ApplicationListener<ApplicationReadyEvent>, SmartLifecycle {
    private final IndexJobStore jobs;
    private final IndexJobExecutor executor;
    private final IndexJobProperties properties;
    private final ScheduledThreadPoolExecutor dispatcher;
    private final AtomicBoolean applicationReady = new AtomicBoolean(false);
    private final Object lifecycleMonitor = new Object();
    private final List<Runnable> stopCallbacks = new ArrayList<>();
    private boolean stopping;
    private boolean workTerminated;
    private ScheduledFuture<?> pollFuture;

    public IndexJobDispatcher(IndexJobStore jobs, IndexJobExecutor executor, IndexJobProperties properties) {
        this.jobs = Objects.requireNonNull(jobs, "jobs is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.properties = Objects.requireNonNull(properties, "properties are required");
        this.dispatcher = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "index-job-dispatcher");
            thread.setDaemon(false);
            return thread;
        }) {
            @Override
            protected void terminated() {
                completeStopCallbacks();
            }
        };
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Objects.requireNonNull(event, "application ready event is required");
        applicationReady.set(true);
        start();
    }

    @Override
    public void start() {
        synchronized (lifecycleMonitor) {
            if (!applicationReady.get() || Objects.nonNull(pollFuture) || stopping) {
                return;
            }
            pollFuture = dispatcher.scheduleWithFixedDelay(this::dispatchScheduled, 0L,
                    TimeUnit.NANOSECONDS.convert(properties.pollDelay()), TimeUnit.NANOSECONDS);
        }
    }

    public void dispatchOnce() {
        Optional<IndexJob> running;
        synchronized (lifecycleMonitor) {
            if (stopping) {
                return;
            }
            running = jobs.startNextAccepted();
        }
        running.ifPresent(this::execute);
    }

    private void dispatchScheduled() {
        try {
            dispatchOnce();
        } catch (RuntimeException exception) {
            // The next fixed-delay poll remains available after a terminal job failure.
        }
    }

    private void execute(IndexJob job) {
        try {
            executor.execute(job);
        } finally {
            logTerminal(job);
        }
    }

    private void logTerminal(IndexJob job) {
        jobs.find(job.id()).filter(IndexJobDispatcher::isTerminal).ifPresent(terminal -> log.info(
                "index-job-terminal repositoryId={} jobId={} operation={} phase={} failureCategory={}",
                terminal.repositoryId().value(), terminal.id().value(), terminal.operation(), terminal.phase(),
                terminal.failureCategory().map(Enum::name).orElse("NONE")));
    }

    private static boolean isTerminal(IndexJob job) {
        return job.phase() == IndexJobPhase.COMPLETE || job.phase() == IndexJobPhase.FAILED;
    }

    @Override
    public void stop() {
        synchronized (lifecycleMonitor) {
            markStopping();
        }
        dispatcher.shutdownNow();
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "stop callback is required");
        boolean runCallbackImmediately = false;
        synchronized (lifecycleMonitor) {
            if (workTerminated) {
                runCallbackImmediately = true;
            } else {
                stopCallbacks.add(callback);
                markStopping();
            }
        }
        if (runCallbackImmediately) {
            runStopCallback(callback);
        } else {
            dispatcher.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        synchronized (lifecycleMonitor) {
            return !stopping && !dispatcher.isShutdown() && Objects.nonNull(pollFuture);
        }
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    private void markStopping() {
        stopping = true;
        if (Objects.nonNull(pollFuture)) {
            pollFuture.cancel(false);
        }
    }

    private void completeStopCallbacks() {
        List<Runnable> callbacks;
        synchronized (lifecycleMonitor) {
            workTerminated = true;
            callbacks = List.copyOf(stopCallbacks);
            stopCallbacks.clear();
        }
        for (Runnable callback : callbacks) {
            runStopCallback(callback);
        }
    }

    private void runStopCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            log.warn("Index job dispatcher stop callback failed", exception);
        }
    }
}
