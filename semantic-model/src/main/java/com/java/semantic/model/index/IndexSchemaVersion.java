package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

public record IndexSchemaVersion(int value) {

    public IndexSchemaVersion {
        ModelValidation.require(value > 0, "index schema version must be positive");
    }
}
