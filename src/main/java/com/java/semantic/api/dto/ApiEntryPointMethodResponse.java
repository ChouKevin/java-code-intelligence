package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;

import java.util.List;
import java.util.Objects;

public record ApiEntryPointMethodResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String description,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) EntryPointType type,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String apiUrl,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> httpMethods,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> swaggerDescriptions,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public ApiEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.API.equals(type)) {
            throw new IllegalArgumentException("API response requires API type");
        }
        httpMethods = List.copyOf(httpMethods);
        swaggerDescriptions = List.copyOf(swaggerDescriptions);
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
