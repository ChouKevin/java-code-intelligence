package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本的事件監聽器探索回應 */
public record DiscoverEventListenersResponse(
        String repoId,
        String analyzedRevision,
        String requestedEventType,
        List<EventListenerCandidateResponse> candidates,
        CandidatePageResponse page,
        List<ListenerObservationSummaryResponse> observationSummaries) {

    public DiscoverEventListenersResponse {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        page = Objects.requireNonNull(page, "page is required");
        observationSummaries = List.copyOf(Objects.requireNonNull(
                observationSummaries, "observationSummaries are required"));
    }
}
