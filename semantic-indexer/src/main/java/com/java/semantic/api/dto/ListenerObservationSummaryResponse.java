package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.SourceRangePayload;

import java.util.List;
import java.util.Objects;

/** 事件監聽器探索診斷的完整摘要 */
public record ListenerObservationSummaryResponse(
        @MonitoringField(MonitoringMode.VALUE) String code,
        @MonitoringField(MonitoringMode.VALUE) long totalCount,
        @MonitoringField(MonitoringMode.SIZE) List<SourceRangePayload> samples) {

    public ListenerObservationSummaryResponse {
        samples = List.copyOf(Objects.requireNonNull(samples, "samples are required"));
    }
}
