package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 結構化概念問題原因的 HTTP 聚合摘要 */
public record ConceptIssueSummaryResponse(@MonitoringField(MonitoringMode.VALUE) String code, @MonitoringField(MonitoringMode.VALUE) int count) {
}
