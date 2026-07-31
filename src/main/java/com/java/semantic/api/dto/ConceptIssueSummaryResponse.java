package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 結構化概念問題原因的 HTTP 聚合摘要 */
public record ConceptIssueSummaryResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) String reason, @ApiMonitoringField(ApiMonitoringMode.VALUE) int count) {
}
