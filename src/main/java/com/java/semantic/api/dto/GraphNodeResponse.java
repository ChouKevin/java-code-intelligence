package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Objects;

/** One normalized node whose source availability and traversal progress are explicit. */
public record GraphNodeResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String nodeId,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String externalSymbol,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String contentState,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String traversalState,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String dispatchKind,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String methodBody,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse declarationRange) {

    public GraphNodeResponse {
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        contentState = Objects.requireNonNull(contentState, "contentState is required");
        traversalState = Objects.requireNonNull(traversalState, "traversalState is required");
        dispatchKind = Objects.requireNonNull(dispatchKind, "dispatchKind is required");
    }
}
