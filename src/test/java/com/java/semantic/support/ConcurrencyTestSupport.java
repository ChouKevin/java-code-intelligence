package com.java.semantic.support;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ConcurrencyTestSupport {

    private ConcurrencyTestSupport() {
        throw new AssertionError("test support class");
    }

    public static void await(CountDownLatch latch, Duration timeout) {
        Objects.requireNonNull(latch, "latch is required");
        Objects.requireNonNull(timeout, "timeout is required");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            if (!latch.await(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch wait interrupted", exception);
        }
    }
}
