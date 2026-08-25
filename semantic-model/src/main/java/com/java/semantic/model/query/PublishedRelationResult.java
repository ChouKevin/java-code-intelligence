package com.java.semantic.model.query;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.index.RelationDocument;

import java.util.List;
import java.util.Objects;

/** Stored relation facts returned without semantic-engine reclassification. */
public record PublishedRelationResult(
        CurrentGeneration generation,
        CodeFactIdentity target,
        List<RelationDocument> relations,
        PublishedRelationPage page) {

    public PublishedRelationResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        target = Objects.requireNonNull(target, "target is required");
        relations = List.copyOf(Objects.requireNonNull(relations, "relations are required"));
        page = Objects.requireNonNull(page, "page is required");
        if (relations.size() != page.returnedCount()) {
            throw new IllegalArgumentException("relations must match page metadata");
        }
    }
}
