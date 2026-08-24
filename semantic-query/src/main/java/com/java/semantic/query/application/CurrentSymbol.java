package com.java.semantic.query.application;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.support.ModelValidation;

import java.util.Objects;

public record CurrentSymbol(CurrentGeneration generation, String symbolId, String canonicalIdentity, String sourcePath) {
    public CurrentSymbol {
        generation = Objects.requireNonNull(generation, "current generation is required");
        symbolId = ModelValidation.requiredText(symbolId, "symbol id");
        canonicalIdentity = ModelValidation.requiredText(canonicalIdentity, "canonical identity");
        sourcePath = ModelValidation.requiredText(sourcePath, "source path");
    }
}
