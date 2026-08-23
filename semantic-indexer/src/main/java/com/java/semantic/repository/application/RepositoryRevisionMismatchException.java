package com.java.semantic.repository.application;

import com.java.semantic.repository.domain.RepositoryRevision;

/** 呼叫者指定的版本與目前快照不同 */
public class RepositoryRevisionMismatchException extends RuntimeException {

    private final RepositoryRevision expectedRevision;
    private final RepositoryRevision currentRevision;

    public RepositoryRevisionMismatchException(
            RepositoryRevision expectedRevision,
            RepositoryRevision currentRevision) {
        super("expected revision does not match current revision");
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public RepositoryRevision getExpectedRevision() {
        return expectedRevision;
    }

    public RepositoryRevision getCurrentRevision() {
        return currentRevision;
    }
}
