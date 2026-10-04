package com.java.semantic.query.source;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounds admission and the subsequent read/search by one synchronous request clock. */
public final class SourceOperationDeadline {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private SourceOperationDeadline() { }

    public static <T> T within(Duration maximum, Supplier<T> operation) {
        Objects.requireNonNull(operation);
        Long previous = CURRENT.get();
        long deadline = System.nanoTime() + maximum.toNanos();
        long effective = Objects.isNull(previous) ? deadline : Math.min(previous, deadline);
        CURRENT.set(effective);
        try {
            T result = operation.get();
            LocalSourceRevisionCatalog.check(effective);
            return result;
        }
        finally {
            if (Objects.isNull(previous)) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static long cap(long proposed) {
        Long current = CURRENT.get();
        return Objects.isNull(current) ? proposed : Math.min(current, proposed);
    }
}
