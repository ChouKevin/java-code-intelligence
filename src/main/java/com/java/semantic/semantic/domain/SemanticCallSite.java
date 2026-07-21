package com.java.semantic.semantic.domain;

import java.util.Objects;

/** 一個呼叫位置的完整證據範圍與語意解析錨點 */
public record SemanticCallSite(SemanticRange range, SemanticPosition anchor) {

    public SemanticCallSite {
        Objects.requireNonNull(range, "range is required");
        Objects.requireNonNull(anchor, "anchor is required");
    }
}
