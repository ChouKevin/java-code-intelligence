package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本的事件監聽器探索回應 */
public record DiscoverEventListenersResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String requestedEventType,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<EventListenerCandidateResponse> candidates,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) PageResponse page,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ListenerObservationSummaryResponse> observationSummaries) {

    public DiscoverEventListenersResponse {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        page = Objects.requireNonNull(page, "page is required");
        observationSummaries = List.copyOf(Objects.requireNonNull(
                observationSummaries, "observationSummaries are required"));
    }
}
