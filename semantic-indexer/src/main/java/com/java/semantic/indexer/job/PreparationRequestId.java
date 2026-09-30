package com.java.semantic.indexer.job;

import java.util.Objects;
import java.util.UUID;

/** Caller-generated correlation identity, persisted before preparation acceptance. */
public record PreparationRequestId(String value) {
    public PreparationRequestId {
        value = Objects.requireNonNull(value, "preparation request id is required");
        if (!UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("preparation request id must be a canonical lowercase UUID");
        }
    }
}
