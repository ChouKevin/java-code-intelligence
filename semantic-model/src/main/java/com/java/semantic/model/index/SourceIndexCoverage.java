package com.java.semantic.model.index;

import com.java.semantic.model.support.ModelValidation;

import java.util.List;
import java.util.Objects;

/** Authorized current-generation source extraction coverage, without semantic conclusions. */
public record SourceIndexCoverage(long indexedSourceCount, List<SourceIndexIssue> issues) {
    public SourceIndexCoverage {
        ModelValidation.require(indexedSourceCount >= 0, "indexed source count must not be negative");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues are required"));
        ModelValidation.require(indexedSourceCount >= issues.size(), "indexed source count must cover issues");
    }
}
