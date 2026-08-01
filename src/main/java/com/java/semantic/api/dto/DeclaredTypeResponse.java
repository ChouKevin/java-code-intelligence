package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Objects;
import java.util.Optional;

/** source spelling 與 repository source 證明的 optional canonical type */
public record DeclaredTypeResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
        @JsonInclude(JsonInclude.Include.NON_ABSENT)
        @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> resolvedType) {

    public DeclaredTypeResponse {
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
    }
}
