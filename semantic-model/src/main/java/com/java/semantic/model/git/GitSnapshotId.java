package com.java.semantic.model.git;

import com.java.semantic.model.support.ModelValidation;

import java.util.UUID;

/** Opaque identity for one immutable complete Git tree snapshot. */
public record GitSnapshotId(String value) {
    public GitSnapshotId {
        value = ModelValidation.requiredText(value, "git snapshot id");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("git snapshot id must be a UUID", exception);
        }
    }

    public static GitSnapshotId create() {
        return new GitSnapshotId(UUID.randomUUID().toString());
    }
}
