package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApiRouteSuggestRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String apiPath,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String httpMethod,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoScope,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotNull @Min(1) @Max(20) Integer limit) {
}
