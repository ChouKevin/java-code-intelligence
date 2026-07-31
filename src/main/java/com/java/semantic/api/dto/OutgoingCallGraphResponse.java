package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** Normalized, bounded outgoing call-graph response. */
public record OutgoingCallGraphResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String rootNodeId,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) GraphTraversalResponse traversal,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<GraphNodeResponse> nodes,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<GraphEdgeResponse> edges,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<GraphWarningResponse> warnings,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<GraphErrorResponse> errors) {

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
