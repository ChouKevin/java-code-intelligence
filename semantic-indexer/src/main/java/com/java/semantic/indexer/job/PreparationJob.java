package com.java.semantic.indexer.job;

/** Common lookup identity; concrete source and index records retain their separate persisted formats. */
public interface PreparationJob {
    String jobId();
    String repositoryId();
    String requestId();
}
