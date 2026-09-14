package com.java.semantic.model.git;

import com.java.semantic.model.support.ModelValidation;

import java.util.UUID;

/** Opaque immutable identity for a prepared Git evidence manifest. */
public record GitEvidenceId(String value) {
    public GitEvidenceId {
        value = ModelValidation.requiredText(value, "git evidence id");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("git evidence id must be a UUID", exception);
        }
    }

    public static GitEvidenceId create() {
        return new GitEvidenceId(UUID.randomUUID().toString());
    }
}
