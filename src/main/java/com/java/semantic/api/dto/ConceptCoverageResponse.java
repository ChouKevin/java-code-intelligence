package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 結構化探索對可信任 production Java 來源的覆蓋摘要 */
public record ConceptCoverageResponse(
        @MonitoringField(MonitoringMode.VALUE) String status,
        @MonitoringField(MonitoringMode.VALUE) int scannedFileCount,
        @MonitoringField(MonitoringMode.VALUE) int extractedFileCount,
        @MonitoringField(MonitoringMode.VALUE) int syntaxFailedFileCount) {
}
