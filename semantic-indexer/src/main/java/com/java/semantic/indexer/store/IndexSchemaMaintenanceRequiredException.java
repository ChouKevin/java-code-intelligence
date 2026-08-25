package com.java.semantic.indexer.store;

/** The worker may read schema readiness but only the schema-maintenance identity may repair it. */
public final class IndexSchemaMaintenanceRequiredException extends RuntimeException {
    public IndexSchemaMaintenanceRequiredException(String detail) {
        super("SCHEMA_MAINTENANCE_REQUIRED: " + detail
                + ". Run the versioned schema bootstrap with the schema-maintenance identity before rebuilding.");
    }
}
