package com.java.semantic.api.dto;

import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.ScheduleTriggerKind;

import java.util.Objects;

public record ScheduleEntryPointMethodResponse(
        String name,
        String description,
        EntryPointType type,
        ScheduleTriggerKind triggerKind,
        String triggerValue) implements EntryPointMethodResponse {

    public ScheduleEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.SCHEDULE.equals(type)) {
            throw new IllegalArgumentException("schedule response requires SCHEDULE type");
        }
    }
}
