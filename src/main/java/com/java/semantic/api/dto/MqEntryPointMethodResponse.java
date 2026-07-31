package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MqBroker;

import java.util.List;
import java.util.Objects;

public record MqEntryPointMethodResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String description,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) EntryPointType type,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) MqBroker broker,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> destinations,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public MqEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.MQ.equals(type)) {
            throw new IllegalArgumentException("MQ response requires MQ type");
        }
        destinations = List.copyOf(destinations);
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
