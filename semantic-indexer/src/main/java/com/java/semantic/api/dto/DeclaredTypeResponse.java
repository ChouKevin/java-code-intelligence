package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.Objects;
import java.util.Optional;

/** source spelling 與 repository source 證明的 optional canonical type */
public record DeclaredTypeResponse(
        @MonitoringField(MonitoringMode.VALUE) String writtenType,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @MonitoringField(MonitoringMode.VALUE) Optional<String> resolvedType) {

    public DeclaredTypeResponse {
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
    }
}
