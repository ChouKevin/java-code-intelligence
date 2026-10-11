package com.java.semantic.document.application;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Immediate, bounded admission for operations that own one cooperative deadline. */
public final class DocumentOperationGate {
    private final Semaphore permits = new Semaphore(DocumentContract.MAX_ACTIVE_OPERATIONS);
    private final Supplier<DocumentDeadline> deadlineFactory;

    public DocumentOperationGate() {
        this(() -> DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT));
    }

    DocumentOperationGate(Supplier<DocumentDeadline> deadlineFactory) {
        this.deadlineFactory = Objects.requireNonNull(deadlineFactory, "deadlineFactory");
    }

    public Permit open() {
        if (!permits.tryAcquire()) {
            throw new DocumentFailure(DocumentFailure.Code.DOCUMENT_BUSY);
        }
        try {
            return new Permit(Objects.requireNonNull(deadlineFactory.get(), "deadline"));
        } catch (RuntimeException | Error failure) {
            permits.release();
            throw failure;
        }
    }

    public final class Permit implements AutoCloseable {
        private final DocumentDeadline deadline;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(DocumentDeadline deadline) {
            this.deadline = deadline;
        }

        public DocumentDeadline deadline() {
            return deadline;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
