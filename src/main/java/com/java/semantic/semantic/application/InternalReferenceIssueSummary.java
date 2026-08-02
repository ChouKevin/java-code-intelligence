package com.java.semantic.semantic.application;

import java.util.Objects;

/** 相同內部 reference 問題代碼的完整計數 */
public record InternalReferenceIssueSummary(InternalReferenceIssueCode code, int count) {

    public InternalReferenceIssueSummary {
        code = Objects.requireNonNull(code, "code is required");
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
