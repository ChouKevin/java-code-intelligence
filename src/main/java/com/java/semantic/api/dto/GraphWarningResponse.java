package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;

/** Expected incomplete semantic outcome, including every exact candidate where applicable. */
public record GraphWarningResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String code,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String message,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String nodeId,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String callExpression,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangePayload callSite,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<MethodTargetPayload> candidates) {

    public GraphWarningResponse {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
    }
}
