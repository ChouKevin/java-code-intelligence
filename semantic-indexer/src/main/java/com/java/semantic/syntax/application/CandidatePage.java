package com.java.semantic.syntax.application;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** 已排序監聽器候選項的一頁結果 */
public record CandidatePage(
        List<EventListenerCandidate> candidates,
        int offset,
        int limit,
        int returnedCount,
        long totalCount,
        boolean hasMore) {

    public CandidatePage {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        Assert.isTrue(offset >= 0, "offset must not be negative");
        Assert.isTrue(limit >= EventListenerDiscoveryConstraints.MIN_LIMIT
                        && limit <= EventListenerDiscoveryConstraints.MAX_LIMIT,
                "limit must be within discovery bounds");
        Assert.isTrue(returnedCount == candidates.size(), "returnedCount must equal candidates size");
        Assert.isTrue(totalCount >= candidates.size(), "totalCount must cover returned candidates");
    }
}
