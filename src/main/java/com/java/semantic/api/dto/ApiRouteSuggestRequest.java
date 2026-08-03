package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApiRouteSuggestRequest(
        @MonitoringField(MonitoringMode.VALUE) @NotBlank String apiPath,
        @MonitoringField(MonitoringMode.VALUE) String httpMethod,
        @MonitoringField(MonitoringMode.VALUE) String repoScope,
        @MonitoringField(MonitoringMode.VALUE) @NotNull @Min(1) @Max(20) Integer limit) {
}
