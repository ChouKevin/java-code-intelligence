package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Objects;

/** Descendant failure captured after a usable outgoing graph fragment was established. */
public record GraphErrorResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) String code, @ApiMonitoringField(ApiMonitoringMode.SIZE) String message, @ApiMonitoringField(ApiMonitoringMode.VALUE) String nodeId) {

    public GraphErrorResponse {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
    }
}
