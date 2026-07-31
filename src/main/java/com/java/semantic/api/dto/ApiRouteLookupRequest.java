package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import jakarta.validation.constraints.NotBlank;

public record ApiRouteLookupRequest(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) @NotBlank String apiPath,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String httpMethod,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoScope) {
}
