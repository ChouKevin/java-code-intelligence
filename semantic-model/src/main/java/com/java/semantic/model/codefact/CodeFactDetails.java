package com.java.semantic.model.codefact;

import com.java.semantic.model.query.CurrentGeneration;

import java.util.List;
import java.util.Objects;

/** Authoritative exact fact payload, deliberately excluding graph and business interpretation. */
public record CodeFactDetails(
        CurrentGeneration generation,
        CodeFact fact,
        SourceRange location,
        List<AnnotationFact> annotations) {

    public CodeFactDetails {
        generation = Objects.requireNonNull(generation, "generation is required");
        fact = Objects.requireNonNull(fact, "code fact is required");
        location = Objects.requireNonNull(location, "location is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
    }
}
