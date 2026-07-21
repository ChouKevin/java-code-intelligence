package com.java.semantic.callgraph.domain;

public record AnalysisError(
        String code,
        String message,
        String detail) {
}
