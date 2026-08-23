package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;
import java.util.Set;

public record ProjectionRequirements(Set<ProjectionName> names) {

    public ProjectionRequirements {
        names = Set.copyOf(Objects.requireNonNull(names, "projection requirements are required"));
        ModelValidation.require(!names.isEmpty(), "projection requirements must not be empty");
    }
}
