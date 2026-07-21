package com.java.semantic.callgraph.application;

import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisWarning;
import com.java.semantic.callgraph.domain.ExplainableCallGraph;

import java.util.List;
import java.util.Objects;

public record CallGraphBuildResult(
        ExplainableCallGraph graph,
        List<AnalysisWarning> warnings,
        List<AnalysisError> errors,
        boolean partial) {

    public CallGraphBuildResult {
        Objects.requireNonNull(graph, "graph is required");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings are required"));
        errors = List.copyOf(Objects.requireNonNull(errors, "errors are required"));
    }
}
