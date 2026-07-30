package com.java.semantic.semantic.domain;

import java.util.List;
import java.util.Objects;

/** 承載實作查詢可用的方法與逐一位置轉換時保留的問題證據 */
public record SemanticImplementationResult(
        List<SemanticMethod> methods,
        List<SemanticImplementationIssueReason> issues) {

    public SemanticImplementationResult {
        methods = List.copyOf(Objects.requireNonNull(methods, "methods are required"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues are required"));
    }
}
