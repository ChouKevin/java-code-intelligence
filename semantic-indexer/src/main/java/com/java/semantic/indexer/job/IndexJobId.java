package com.java.semantic.indexer.job;

import java.util.Objects;
import java.util.UUID;

/** Opaque job identity; it deliberately carries no source or credential details. */
public record IndexJobId(String value) {
    public IndexJobId {
        value = Objects.requireNonNull(value, "job id is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException("job id is required");
        }
    }

    public static IndexJobId create() {
        return new IndexJobId(UUID.randomUUID().toString());
    }
}
