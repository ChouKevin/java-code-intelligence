package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** Normalized, bounded outgoing call-graph response. */
public record OutgoingCallGraphResponse(
        String status,
        String analyzedRevision,
        String rootNodeId,
        GraphTraversalResponse traversal,
        List<GraphNodeResponse> nodes,
        List<GraphEdgeResponse> edges,
        List<GraphWarningResponse> warnings,
        List<GraphErrorResponse> errors) {

    public OutgoingCallGraphResponse {
        status = Objects.requireNonNull(status, "status is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        rootNodeId = Objects.requireNonNull(rootNodeId, "rootNodeId is required");
        traversal = Objects.requireNonNull(traversal, "traversal is required");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes are required"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges are required"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings are required"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors are required"));
    }
}
