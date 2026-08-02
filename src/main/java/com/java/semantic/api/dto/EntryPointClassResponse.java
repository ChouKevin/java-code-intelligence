package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

public record EntryPointClassResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String description,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> basePaths,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<EntryPointMethodResponse> methods) {

    public EntryPointClassResponse {
        basePaths = List.copyOf(basePaths);
        methods = List.copyOf(methods);
    }
}
