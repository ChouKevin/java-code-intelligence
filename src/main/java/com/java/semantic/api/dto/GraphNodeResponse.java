package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;

import java.util.Objects;
import java.util.List;

/** One normalized node whose source availability and traversal progress are explicit. */
public record GraphNodeResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String nodeId,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String externalSymbol,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String contentState,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String traversalState,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String dispatchKind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public GraphNodeResponse {
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        contentState = Objects.requireNonNull(contentState, "contentState is required");
        traversalState = Objects.requireNonNull(traversalState, "traversalState is required");
        dispatchKind = Objects.requireNonNull(dispatchKind, "dispatchKind is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }

    public GraphNodeResponse(
            String nodeId,
            MethodTargetPayload target,
            String externalSymbol,
            String contentState,
            String traversalState,
            String dispatchKind,
            TextRangePayload declarationRange) {
        this(nodeId, target, externalSymbol, contentState, traversalState, dispatchKind, declarationRange, List.of());
    }
}
