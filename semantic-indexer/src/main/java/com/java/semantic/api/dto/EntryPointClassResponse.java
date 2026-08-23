package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;

public record EntryPointClassResponse(
        @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
        @MonitoringField(MonitoringMode.SIZE) String description,
        @MonitoringField(MonitoringMode.SIZE) List<String> basePaths,
        @MonitoringField(MonitoringMode.SIZE) List<EntryPointMethodResponse> methods) {

    public EntryPointClassResponse {
        basePaths = List.copyOf(basePaths);
        methods = List.copyOf(methods);
    }
}
