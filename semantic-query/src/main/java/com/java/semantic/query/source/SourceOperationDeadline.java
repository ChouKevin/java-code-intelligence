package com.java.semantic.query.source;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounds admission and the subsequent read/search by one synchronous request clock. */
public final class SourceOperationDeadline {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private SourceOperationDeadline() { }

    public static <T> T within(Duration maximum, Supplier<T> operation) {
        return within(maximum, operation, () -> new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT));
    }

    public static <T> T within(Duration maximum, Supplier<T> operation,
            Supplier<? extends RuntimeException> timeout) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(timeout);
        Long previous = CURRENT.get();
        long deadline = System.nanoTime() + maximum.toNanos();
        long effective = Objects.isNull(previous) ? deadline : Math.min(previous, deadline);
        CURRENT.set(effective);
        try {
            T result = operation.get();
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= effective) throw timeout.get();
            return result;
        }
        finally {
            if (Objects.isNull(previous)) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static long cap(long proposed) {
        Long current = CURRENT.get();
        return Objects.isNull(current) ? proposed : Math.min(current, proposed);
    }
}
