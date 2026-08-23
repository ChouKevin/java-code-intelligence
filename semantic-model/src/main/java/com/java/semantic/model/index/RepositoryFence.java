package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

public record RepositoryFence(long value) {

    public RepositoryFence {
        ModelValidation.require(value >= 0, "repository fence must not be negative");
    }
}
