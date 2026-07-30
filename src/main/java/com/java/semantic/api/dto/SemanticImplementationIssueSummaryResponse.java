package com.java.semantic.api.dto;

import java.util.Objects;

/** 一種方法實作探索問題的累計數量 */
public record SemanticImplementationIssueSummaryResponse(String code, int count) {

    public SemanticImplementationIssueSummaryResponse {
        code = Objects.requireNonNull(code, "code is required");
    }
}
