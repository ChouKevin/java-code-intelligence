package com.java.semantic.indexer.job;

/** Raised inside Task-6 build work once the exact repository authority is lost. */
public final class ClaimLostException extends RuntimeException {
    public ClaimLostException(IndexJobId jobId) {
        super("repository claim lost for " + jobId.value());
    }
}
