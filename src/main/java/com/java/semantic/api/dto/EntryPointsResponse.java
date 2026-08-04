package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;

public record EntryPointsResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.SIZE) List<EntryPointClassResponse> entryPoints) {

    public EntryPointsResponse {
        entryPoints = List.copyOf(entryPoints);
    }
}
