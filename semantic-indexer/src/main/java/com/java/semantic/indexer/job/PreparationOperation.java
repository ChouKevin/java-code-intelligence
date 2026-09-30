package com.java.semantic.indexer.job;

/** The original caller intent, distinct from the dispatcher's execution operation. */
public enum PreparationOperation {
    REFRESH_REPOSITORY_METADATA,
    PREPARE_CODEBASE,
    PREPARE_REVIEW
}
