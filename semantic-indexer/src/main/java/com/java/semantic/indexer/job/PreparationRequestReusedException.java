package com.java.semantic.indexer.job;

import java.util.Objects;

/** A persisted repository-scoped request cannot be submitted again. */
public final class PreparationRequestReusedException extends RuntimeException {
    private final IndexJobId jobId;
    private final PreparationRequestId requestId;

    public PreparationRequestReusedException(IndexJobId jobId, PreparationRequestId requestId) {
        super("preparation request was already accepted");
        this.jobId = Objects.requireNonNull(jobId, "original job id is required");
        this.requestId = Objects.requireNonNull(requestId, "original request id is required");
    }

    public IndexJobId jobId() { return jobId; }
    public PreparationRequestId requestId() { return requestId; }
}
