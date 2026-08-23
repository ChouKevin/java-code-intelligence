package com.java.semantic.trie;

import java.util.List;
import java.util.Objects;

/** 將路由參照與其可解釋結構命中原因綁定 */
public record ApiRouteMatch(ApiEntryPointRef ref, List<ApiRouteMatchReason> matchReasons) {

    public ApiRouteMatch {
        ref = Objects.requireNonNull(ref, "ref is required");
        matchReasons = List.copyOf(Objects.requireNonNull(matchReasons, "matchReasons are required"));
    }
}
