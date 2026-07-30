package com.java.semantic.syntax.application;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 監聽器診斷的完整總數與有限位置樣本 */
public record ListenerObservationSummary(
        ListenerObservationCode code,
        long totalCount,
        List<ListenerSourceLocation> sourceLocations) {

    public ListenerObservationSummary {
        Objects.requireNonNull(code, "code is required");
        Assert.isTrue(totalCount > 0, "totalCount must be positive");
        sourceLocations = List.copyOf(Objects.requireNonNull(sourceLocations, "sourceLocations are required"));
        Assert.isTrue(sourceLocations.size() <= EventListenerDiscoveryConstraints.OBSERVATION_SAMPLE_LIMIT,
                "source location samples exceed the observation limit");
        Assert.isTrue(totalCount >= sourceLocations.size(), "totalCount must cover source location samples");
    }
}
