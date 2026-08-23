package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

public record SourceArtifactId(String value) {

    public SourceArtifactId {
        value = ModelValidation.sha256(value, "source artifact id");
    }
}
