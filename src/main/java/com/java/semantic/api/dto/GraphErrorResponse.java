package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.Objects;

/** Descendant failure captured after a usable outgoing graph fragment was established. */
public record GraphErrorResponse(@MonitoringField(MonitoringMode.VALUE) String code, @MonitoringField(MonitoringMode.SIZE) String message, @MonitoringField(MonitoringMode.VALUE) String nodeId) {

    public GraphErrorResponse {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        nodeId = Objects.requireNonNull(nodeId, "nodeId is required");
    }
}
