package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** 事件監聽器探索診斷的完整摘要 */
public record ListenerObservationSummaryResponse(
        String code,
        long totalCount,
        List<SourceRangeResponse> samples) {

    public ListenerObservationSummaryResponse {
        samples = List.copyOf(Objects.requireNonNull(samples, "samples are required"));
    }
}
