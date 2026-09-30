package com.java.semantic.indexer.job;

/** No job is visible under the authorized repository and exact selector. */
public final class IndexJobNotFoundException extends RuntimeException {
    public IndexJobNotFoundException() {
        super("job not found");
    }
}
