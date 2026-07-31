package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;

import java.util.Objects;

public record ScheduleEntryPointMethodResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String description,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) EntryPointType type,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) ScheduleTriggerKind triggerKind,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String triggerValue,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public ScheduleEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.SCHEDULE.equals(type)) {
            throw new IllegalArgumentException("schedule response requires SCHEDULE type");
        }
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
