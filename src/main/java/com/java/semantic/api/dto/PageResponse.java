package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 固定版本結果的一頁計數 */
public record PageResponse(
        @MonitoringField(MonitoringMode.VALUE) int offset,
        @MonitoringField(MonitoringMode.VALUE) int limit,
        @MonitoringField(MonitoringMode.VALUE) int returnedCount,
        @MonitoringField(MonitoringMode.VALUE) long totalCount,
        @MonitoringField(MonitoringMode.VALUE) boolean hasMore) {

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
