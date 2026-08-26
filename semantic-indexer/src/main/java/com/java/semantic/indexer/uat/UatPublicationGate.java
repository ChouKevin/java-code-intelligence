package com.java.semantic.indexer.uat;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One bounded, in-memory UAT pause cycle. Cycle identifiers are evidence, never client tokens. */
public final class UatPublicationGate implements PublicationGate {
    private final Duration timeout;
    private long lastCycleId;
    private Cycle current;

    public UatPublicationGate(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "publication timeout is required");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("publication timeout must be positive");
        }
    }

    public synchronized long arm() {
        if (Objects.nonNull(current)) {
            throw new IllegalStateException("publication gate already has an active cycle");
        }
        lastCycleId++;
        current = new Cycle(lastCycleId, new CountDownLatch(1), new CompletableFuture<>());
        return lastCycleId;
    }

    public synchronized void release() {
        Cycle released = current;
        current = null; // cs-allow: field assignment is not a null comparison
        if (Objects.nonNull(released)) {
            released.release();
        }
    }

    @Override
    public void abortPublication() {
        release();
    }

    @Override
    public void awaitPublication() {
        Cycle cycle = currentCycle();
        if (Objects.isNull(cycle)) {
            return;
        }
        cycle.signalReached();
        try {
            if (!cycle.releaseLatch().await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new PublicationGateTimeoutException();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicationGateInterruptedException(exception);
        } finally {
            clear(cycle);
        }
    }

    /** Waits for the active build to enter the publication boundary without changing the cycle. */
    public long awaitReachedPublication() {
        Cycle cycle = currentCycle();
        if (Objects.isNull(cycle)) {
            throw new PublicationObservationUnavailableException();
        }
        try {
            return cycle.reached().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new PublicationObservationTimeoutException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicationObservationInterruptedException(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof PublicationObservationUnavailableException unavailable) {
                throw unavailable;
            }
            throw new IllegalStateException("UAT publication observation failed", cause);
        }
    }

    private synchronized Cycle currentCycle() {
        return current;
    }

    private synchronized void clear(Cycle cycle) {
        if (current == cycle) { // cs-allow: identity prevents a completed cycle clearing a newly armed cycle
            current = null; // cs-allow: field assignment is not a null comparison
        }
    }

    private record Cycle(long cycleId, CountDownLatch releaseLatch, CompletableFuture<Long> reached) {
        private Cycle {
            releaseLatch = Objects.requireNonNull(releaseLatch, "publication release latch is required");
            reached = Objects.requireNonNull(reached, "publication reached signal is required");
        }

        private void signalReached() {
            reached.complete(cycleId);
        }

        private void release() {
            releaseLatch.countDown();
            reached.completeExceptionally(new PublicationObservationUnavailableException());
        }
    }

    public static final class PublicationGateTimeoutException extends RuntimeException {
        public PublicationGateTimeoutException() {
            super("UAT publication gate timed out");
        }
    }

    public static final class PublicationGateInterruptedException extends RuntimeException {
        public PublicationGateInterruptedException(InterruptedException cause) {
            super("UAT publication gate was interrupted", cause);
        }
    }

    public static final class PublicationObservationUnavailableException extends IllegalStateException {
        public PublicationObservationUnavailableException() {
            super("UAT publication gate has no active unreached cycle");
        }
    }

    public static final class PublicationObservationTimeoutException extends RuntimeException {
        public PublicationObservationTimeoutException(TimeoutException cause) {
            super("UAT publication observation timed out", cause);
        }
    }

    public static final class PublicationObservationInterruptedException extends RuntimeException {
        public PublicationObservationInterruptedException(InterruptedException cause) {
            super("UAT publication observation was interrupted", cause);
        }
    }
}
