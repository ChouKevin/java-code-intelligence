package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 結構化探索固定版本結果的一頁計數 */
public record ConceptPageResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) long totalCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean hasMore) {
}
