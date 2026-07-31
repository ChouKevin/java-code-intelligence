package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 事件監聽器候選項的分頁回應 */
public record CandidatePageResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) long totalCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean hasMore) {
}
