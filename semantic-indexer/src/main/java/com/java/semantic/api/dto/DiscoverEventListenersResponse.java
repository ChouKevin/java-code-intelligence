package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本的事件監聽器探索回應 */
public record DiscoverEventListenersResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.VALUE) String requestedEventType,
        @MonitoringField(MonitoringMode.SIZE) List<EventListenerCandidateResponse> candidates,
        @MonitoringField(MonitoringMode.NESTED) PageResponse page,
        @MonitoringField(MonitoringMode.SIZE) List<ListenerObservationSummaryResponse> observationSummaries,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public DiscoverEventListenersResponse {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        page = Objects.requireNonNull(page, "page is required");
        observationSummaries = List.copyOf(Objects.requireNonNull(
                observationSummaries, "observationSummaries are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
