package com.java.semantic.syntax.application;

import com.java.semantic.model.codefact.SourceRange;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 監聽器診斷的完整總數與有限位置樣本 */
public record ListenerObservationSummary(
        ListenerObservationCode code,
        long totalCount,
        List<SourceRange> declarationRanges) {

    public ListenerObservationSummary {
        Objects.requireNonNull(code, "code is required");
        Assert.isTrue(totalCount > 0, "totalCount must be positive");
        declarationRanges = List.copyOf(Objects.requireNonNull(declarationRanges, "declarationRanges are required"));
        Assert.isTrue(declarationRanges.size() <= EventListenerDiscoveryConstraints.OBSERVATION_SAMPLE_LIMIT,
                "source location samples exceed the observation limit");
        Assert.isTrue(totalCount >= declarationRanges.size(), "totalCount must cover source location samples");
    }
}
