package com.java.semantic.document.application;

import java.time.Duration;
import java.util.function.LongSupplier;

/** Checked between bounded units; does not claim to interrupt filesystem/JGit calls. */
public final class DocumentDeadline {
    private final LongSupplier clock;
    private final long started;
    private final long duration;
    private DocumentDeadline(Duration timeout, LongSupplier clock) {
        this.clock = clock;
        this.started = clock.getAsLong();
        this.duration = timeout.toNanos();
    }
    public static DocumentDeadline after(Duration timeout) { return new DocumentDeadline(timeout, System::nanoTime); }
    static DocumentDeadline after(Duration timeout, LongSupplier clock) { return new DocumentDeadline(timeout, clock); }
    public long remainingNanos() { return Math.max(0, duration - (clock.getAsLong() - started)); }
    public void check() {
        if (Thread.currentThread().isInterrupted() || remainingNanos() == 0) {
            throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_TIMEOUT);
        }
    }
}
