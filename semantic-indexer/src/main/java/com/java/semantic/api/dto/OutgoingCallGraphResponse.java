package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** Normalized, bounded outgoing call-graph response. */
public record OutgoingCallGraphResponse(
        @MonitoringField(MonitoringMode.VALUE) String status,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.VALUE) String rootNodeId,
        @MonitoringField(MonitoringMode.NESTED) GraphTraversalResponse traversal,
        @MonitoringField(MonitoringMode.SIZE) List<GraphNodeResponse> nodes,
        @MonitoringField(MonitoringMode.SIZE) List<GraphEdgeResponse> edges,
        @MonitoringField(MonitoringMode.SIZE) List<GraphWarningResponse> warnings,
        @MonitoringField(MonitoringMode.SIZE) List<GraphErrorResponse> errors) {

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
