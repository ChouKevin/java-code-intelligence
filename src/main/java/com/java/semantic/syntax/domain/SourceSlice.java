package com.java.semantic.syntax.domain;

import java.util.Objects;

/** 在 snapshot 期間複製出的原始碼片段 */
public record SourceSlice(SyntaxRange range, String text) {

    public SourceSlice {
        Objects.requireNonNull(range, "range is required");
        Objects.requireNonNull(text, "text is required");
    }
}
