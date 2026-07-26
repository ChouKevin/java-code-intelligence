package com.java.semantic.api.dto;

import com.java.semantic.trie.ApiRouteObservationCode;
import org.springframework.util.Assert;

import java.util.Objects;

/** API 路由候選回應中描述結果範圍的型別化觀察 */
public record ApiRouteObservationResponse(ApiRouteObservationCode code, String description) {

    public ApiRouteObservationResponse {
        code = Objects.requireNonNull(code, "code is required");
        Assert.hasText(description, "description is required");
    }
}
