package com.java.semantic.model.git;

import com.java.semantic.model.support.ModelValidation;

import java.util.UUID;

/** Opaque identity for an immutable direct comparison of two exact Git trees. */
public record GitComparisonId(String value) {
    public GitComparisonId {
        value = ModelValidation.requiredText(value, "git comparison id");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("git comparison id must be a UUID", exception);
        }
    }

    public static GitComparisonId create() {
        return new GitComparisonId(UUID.randomUUID().toString());
    }
}
