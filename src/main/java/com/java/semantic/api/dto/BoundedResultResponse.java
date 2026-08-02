package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 有界結果集合的完整計數 */
public record BoundedResultResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int totalCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean truncated) {

    public BoundedResultResponse {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (returnedCount < 0) {
            throw new IllegalArgumentException("returnedCount must not be negative");
        }
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must not be negative");
        }
        if (returnedCount > totalCount) {
            throw new IllegalArgumentException("returnedCount must not exceed totalCount");
        }
        boolean derivedTruncated = totalCount > returnedCount;
        if (truncated != derivedTruncated) {
            throw new IllegalArgumentException("truncated must match the result counts");
        }
    }
}
