package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** source binding issue code 的 grouped count */
public record SourceSymbolIssueSummaryResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String code,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int count) {
}
