package com.java.semantic.callgraph.domain;

public record AnalysisWarning(
        String code,
        String message,
        String location) {
}
