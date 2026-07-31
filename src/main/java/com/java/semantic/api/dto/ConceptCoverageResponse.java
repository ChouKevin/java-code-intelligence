package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 結構化探索對可信任 production Java 來源的覆蓋摘要 */
public record ConceptCoverageResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int scannedFileCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int extractedFileCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int syntaxFailedFileCount) {
}
