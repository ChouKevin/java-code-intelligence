package com.java.semantic.syntax.application;

import java.util.Objects;

import org.springframework.util.Assert;

/** 單一結構化概念問題原因的聚合數量 */
public record ConceptIssueSummary(ConceptIssueReason reason, int count) {

    public ConceptIssueSummary {
        reason = Objects.requireNonNull(reason, "reason is required");
        Assert.isTrue(count > 0, "count must be positive");
    }
}
