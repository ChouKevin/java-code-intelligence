package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** source binding issue code 的 grouped count */
public record SourceSymbolIssueSummaryResponse(
        @MonitoringField(MonitoringMode.VALUE) String code,
        @MonitoringField(MonitoringMode.VALUE) int count) {
}
