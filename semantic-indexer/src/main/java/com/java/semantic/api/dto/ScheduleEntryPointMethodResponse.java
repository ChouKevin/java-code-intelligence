package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;

import java.util.Objects;

public record ScheduleEntryPointMethodResponse(
        @MonitoringField(MonitoringMode.VALUE) String name,
        @MonitoringField(MonitoringMode.SIZE) String description,
        @MonitoringField(MonitoringMode.VALUE) EntryPointType type,
        @MonitoringField(MonitoringMode.VALUE) ScheduleTriggerKind triggerKind,
        @MonitoringField(MonitoringMode.SIZE) String triggerValue,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public ScheduleEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.SCHEDULE.equals(type)) {
            throw new IllegalArgumentException("schedule response requires SCHEDULE type");
        }
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
