package com.java.semantic.semantic.domain;

import java.util.Objects;

/** 原始碼中的區間,起訖皆為零基 */
public record SemanticRange(SemanticPosition start, SemanticPosition end) {

    public SemanticRange {
        Objects.requireNonNull(start, "start is required");
        Objects.requireNonNull(end, "end is required");
    }
}
