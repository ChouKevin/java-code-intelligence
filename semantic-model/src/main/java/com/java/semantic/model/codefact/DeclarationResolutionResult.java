package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;

import java.util.Optional;
import java.util.Objects;

/** Selected declaration, when an indexed declaration matches the supplied context. */
public record DeclarationResolutionResult(CurrentGeneration generation, Optional<CodeFactSummary> declaration) {

    public DeclarationResolutionResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        declaration = Objects.requireNonNull(declaration, "declaration is required");
    }
}
