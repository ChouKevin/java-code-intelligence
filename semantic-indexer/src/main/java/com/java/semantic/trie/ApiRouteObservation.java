package com.java.semantic.trie;

import org.springframework.util.Assert;

import java.util.Objects;

/** API 路由查詢中供呼叫端理解結果範圍的型別化觀察 */
public record ApiRouteObservation(ApiRouteObservationCode code, String description) {

    public ApiRouteObservation {
        code = Objects.requireNonNull(code, "code is required");
        Assert.hasText(description, "description is required");
    }
}
