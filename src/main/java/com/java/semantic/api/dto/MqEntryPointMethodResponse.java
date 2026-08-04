package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MqBroker;

import java.util.List;
import java.util.Objects;

public record MqEntryPointMethodResponse(
        @MonitoringField(MonitoringMode.VALUE) String name,
        @MonitoringField(MonitoringMode.SIZE) String description,
        @MonitoringField(MonitoringMode.VALUE) EntryPointType type,
        @MonitoringField(MonitoringMode.VALUE) MqBroker broker,
        @MonitoringField(MonitoringMode.SIZE) List<String> destinations,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public MqEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.MQ.equals(type)) {
            throw new IllegalArgumentException("MQ response requires MQ type");
        }
        destinations = List.copyOf(destinations);
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
