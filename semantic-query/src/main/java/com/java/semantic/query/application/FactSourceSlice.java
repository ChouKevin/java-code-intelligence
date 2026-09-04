package com.java.semantic.query.application;

import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.query.CurrentGeneration;

import java.util.Objects;

/** Application-only source materialization retaining separate displayed and authoritative ranges. */
public record FactSourceSlice(
        CurrentGeneration generation,
        SourceRange sourceRange,
        SourceRange factRange,
        String fileContent) {

    public FactSourceSlice {
        generation = Objects.requireNonNull(generation, "generation is required");
        sourceRange = Objects.requireNonNull(sourceRange, "source range is required");
        factRange = Objects.requireNonNull(factRange, "fact range is required");
        fileContent = Objects.requireNonNull(fileContent, "file content is required");
        if (!sourceRange.sourceFile().equals(factRange.sourceFile())) {
            throw new IndexContractMismatchException();
        }
    }
}
