package com.java.semantic.syntax.domain;

import java.util.Objects;

/** 原始碼中的半開區間，起訖皆為零基 */
public record SyntaxRange(SyntaxPosition start, SyntaxPosition end) {

    public SyntaxRange {
        Objects.requireNonNull(start, "start is required");
        Objects.requireNonNull(end, "end is required");
    }
}
