package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import jakarta.validation.constraints.NotBlank;

public record ApiRouteLookupRequest(
        @MonitoringField(MonitoringMode.VALUE) @NotBlank String apiPath,
        @MonitoringField(MonitoringMode.VALUE) String httpMethod,
        @MonitoringField(MonitoringMode.VALUE) String repoScope) {
}
