package com.java.semantic.model.query;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.index.RelationDocument;

import java.util.List;
import java.util.Objects;

/** Bounded graph assembled directly from stored CALLS relation occurrences. */
public record PublishedCallGraphResult(
        CurrentGeneration generation,
        PublishedGraphDirection direction,
        MethodTarget root,
        int depth,
        int depthTwoNodeBudget,
        int expandedDepthTwoNodeCount,
        boolean nodeBudgetReached,
        List<PublishedGraphNode> nodes,
        List<RelationDocument> edges) {

    public PublishedCallGraphResult {
        generation = Objects.requireNonNull(generation, "generation is required");
        direction = Objects.requireNonNull(direction, "direction is required");
        root = Objects.requireNonNull(root, "root is required");
        if (depth < 1 || depth > 2 || depthTwoNodeBudget < 0 || expandedDepthTwoNodeCount < 0
                || expandedDepthTwoNodeCount > depthTwoNodeBudget) {
            throw new IllegalArgumentException("call graph traversal metadata is invalid");
        }
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes are required"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges are required"));
    }
}
