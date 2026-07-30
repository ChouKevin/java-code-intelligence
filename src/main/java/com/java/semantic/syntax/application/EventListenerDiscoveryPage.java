package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Objects;

/** 一次事件監聽器探索的候選結果與完整診斷摘要 */
public record EventListenerDiscoveryPage(
        CandidatePage candidates,
        List<ListenerObservationSummary> observations) {

    public EventListenerDiscoveryPage {
        Objects.requireNonNull(candidates, "candidates are required");
        observations = List.copyOf(Objects.requireNonNull(observations, "observations are required"));
    }

    public static EventListenerDiscoveryPage empty(int offset, int limit) {
        return new EventListenerDiscoveryPage(
                new CandidatePage(List.of(), offset, limit, 0, 0, false), List.of());
    }
}
