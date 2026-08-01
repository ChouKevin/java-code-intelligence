package com.java.semantic.syntax.application;

import java.util.List;
import java.util.Objects;

/**
 * 將 resolver 產生的 symbol evidence 與 application 計算的後續查詢分離
 */
public record NavigableSourceSymbolCandidate(
        SourceSymbolCandidate candidate,
        List<DiscoveryFollowUp> availableFollowUps) {

    public NavigableSourceSymbolCandidate {
        candidate = Objects.requireNonNull(candidate, "candidate");
        availableFollowUps = List.copyOf(
                Objects.requireNonNull(availableFollowUps, "availableFollowUps"));
    }
}
