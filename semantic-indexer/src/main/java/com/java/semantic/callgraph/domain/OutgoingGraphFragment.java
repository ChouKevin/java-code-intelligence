package com.java.semantic.callgraph.domain;

import com.java.semantic.model.repository.RepositoryRevision;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** Complete normalized response-local graph fragment for one exact repository revision. */
public record OutgoingGraphFragment(
        GraphAnalysisStatus status,
        RepositoryRevision analyzedRevision,
        CallNodeId rootNodeId,
        GraphTraversal traversal,
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<GraphWarning> warnings,
        List<GraphError> errors) {

    public OutgoingGraphFragment {
        status = Objects.requireNonNull(status, "status is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        rootNodeId = Objects.requireNonNull(rootNodeId, "rootNodeId is required");
        traversal = Objects.requireNonNull(traversal, "traversal is required");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes are required"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges are required"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings are required"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors are required"));
        CallNodeId requiredRootNodeId = rootNodeId;
        Assert.isTrue(nodes.stream().anyMatch(node -> requiredRootNodeId.equals(node.nodeId())),
                "root node must be present");
        if (GraphAnalysisStatus.SUCCESS.equals(status)) {
            Assert.isTrue(warnings.isEmpty() && errors.isEmpty(),
                    "successful fragment must not contain warnings or errors");
        } else {
            Assert.isTrue(!warnings.isEmpty() || !errors.isEmpty(),
                    "partial fragment requires warnings or errors");
        }
    }
}
