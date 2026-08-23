package com.java.semantic.trie;

import java.util.List;
import java.util.Objects;

/** 一次路由查詢的候選命中與範圍觀察 */
public record ApiRouteMatchBatch(List<ApiRouteMatch> matches, List<ApiRouteObservation> observations) {

    public ApiRouteMatchBatch {
        matches = List.copyOf(Objects.requireNonNull(matches, "matches are required"));
        observations = List.copyOf(Objects.requireNonNull(observations, "observations are required"));
    }
}
