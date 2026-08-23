package com.java.semantic.indexer.job;

/** Stable categories suitable for an administrative job status response. */
public enum IndexFailureCategory {
    WORKER_INTERRUPTED, SOURCE_UNAVAILABLE, SCHEMA_REBUILD_REQUIRED, PUBLICATION_CONFLICT, VALIDATION_FAILED
}
