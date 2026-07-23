package com.java.semantic.semantic.adapter.jdtls;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotent handle for a JDT workspace activity. */
final class WorkspaceActivityLease implements AutoCloseable {

    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    WorkspaceActivityLease(Runnable release) {
        this.release = Objects.requireNonNull(release, "release is required");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
