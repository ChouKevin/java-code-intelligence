package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;

/** 事件監聽器探索診斷的完整摘要 */
public record ListenerObservationSummaryResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String code,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) long totalCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<SourceRangePayload> samples) {

    public ListenerObservationSummaryResponse {
        samples = List.copyOf(Objects.requireNonNull(samples, "samples are required"));
    }
}
