package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;

import java.util.List;
import java.util.Objects;

public record ApiEntryPointMethodResponse(
        @MonitoringField(MonitoringMode.VALUE) String name,
        @MonitoringField(MonitoringMode.SIZE) String description,
        @MonitoringField(MonitoringMode.VALUE) EntryPointType type,
        @MonitoringField(MonitoringMode.VALUE) String apiUrl,
        @MonitoringField(MonitoringMode.SIZE) List<String> httpMethods,
        @MonitoringField(MonitoringMode.SIZE) List<String> swaggerDescriptions,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

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
