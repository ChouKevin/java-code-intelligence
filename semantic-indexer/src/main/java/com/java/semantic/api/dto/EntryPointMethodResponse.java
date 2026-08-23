package com.java.semantic.api.dto;

import com.java.semantic.syntax.domain.EntryPointType;

public sealed interface EntryPointMethodResponse
        permits ApiEntryPointMethodResponse, MqEntryPointMethodResponse, ScheduleEntryPointMethodResponse {

    String name();

    String description();

    EntryPointType type();
}
