package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 固定版本結果的一頁計數 */
public record PageResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int offset,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int limit,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) long totalCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean hasMore) {

    public PageResponse {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
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
        boolean derivedHasMore = (long) offset + returnedCount < totalCount;
        if (hasMore != derivedHasMore) {
            throw new IllegalArgumentException("hasMore must match the page counts");
        }
    }
}
