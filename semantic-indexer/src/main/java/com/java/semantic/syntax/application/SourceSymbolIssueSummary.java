package com.java.semantic.syntax.application;

import java.util.Objects;

/** 同一 issue code 的 deterministic count */
public record SourceSymbolIssueSummary(SourceSymbolIssueCode code, int count) {

    public SourceSymbolIssueSummary {
        code = Objects.requireNonNull(code, "code is required");
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
