package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;

import java.util.Objects;
import java.util.List;

/** One normalized node whose source availability and traversal progress are explicit. */
public record GraphNodeResponse(
        @MonitoringField(MonitoringMode.VALUE) String nodeId,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.VALUE) String externalSymbol,
        @MonitoringField(MonitoringMode.VALUE) String contentState,
        @MonitoringField(MonitoringMode.VALUE) String traversalState,
        @MonitoringField(MonitoringMode.VALUE) String dispatchKind,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

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
