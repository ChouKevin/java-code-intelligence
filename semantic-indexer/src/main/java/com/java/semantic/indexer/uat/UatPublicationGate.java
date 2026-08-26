package com.java.semantic.indexer.uat;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        current = new Cycle(lastCycleId, new CountDownLatch(1));
        return lastCycleId;
    }

    public synchronized void release() {
        Cycle released = current;
        current = null; // cs-allow: field assignment is not a null comparison
        if (Objects.nonNull(released)) {
            released.latch().countDown();
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
        try {
            if (!cycle.latch().await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new PublicationGateTimeoutException();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PublicationGateInterruptedException(exception);
        } finally {
            clear(cycle);
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

    private record Cycle(long cycleId, CountDownLatch latch) {
        private Cycle {
            latch = Objects.requireNonNull(latch, "publication latch is required");
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
}
