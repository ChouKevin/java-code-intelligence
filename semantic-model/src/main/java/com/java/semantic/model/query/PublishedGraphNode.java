package com.java.semantic.model.query;

import com.java.semantic.model.codefact.RelationTarget;

import java.util.Objects;

/** A response-local graph node retaining the persisted internal/external target classification. */
public record PublishedGraphNode(RelationTarget target, int depth, boolean expanded) {

    public PublishedGraphNode {
        target = Objects.requireNonNull(target, "graph node target is required");
        if (depth < 0 || depth > 2) {
            throw new IllegalArgumentException("graph node depth is invalid");
        }
    }
}
