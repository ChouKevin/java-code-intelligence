package com.java.semantic.document.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Advances only a test-owned clock, never a process clock or shared scheduler. */
public final class DocumentClockFixture {
    private final AtomicLong now = new AtomicLong();
    public DocumentDeadline deadline() { return DocumentDeadline.after(Duration.ofSeconds(5), now::get); }
    public void expire() { now.set(5_000_000_000L); }
}
