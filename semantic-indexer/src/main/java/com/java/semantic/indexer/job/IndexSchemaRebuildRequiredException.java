package com.java.semantic.indexer.job;

/** A schema incompatibility is an administrative maintenance decision, never an automatic publication. */
public final class IndexSchemaRebuildRequiredException extends RuntimeException {
    public IndexSchemaRebuildRequiredException() {
        super("SCHEMA_REBUILD_REQUIRED");
    }
}
