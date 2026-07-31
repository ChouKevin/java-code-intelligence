package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 方法實作探索套用後的候選數量限制 */
public record MethodImplementationLimitsResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int candidateLimit,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int totalCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean truncated) {
}
