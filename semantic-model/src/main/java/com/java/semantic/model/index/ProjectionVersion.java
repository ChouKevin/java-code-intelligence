package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record ProjectionVersion(ProjectionName name, int version) {

    public ProjectionVersion {
        name = Objects.requireNonNull(name, "projection name is required");
        ModelValidation.require(version > 0, "projection version must be positive");
    }
}
