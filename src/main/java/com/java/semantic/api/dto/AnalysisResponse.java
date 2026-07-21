package com.java.semantic.api.dto;

import com.java.semantic.callgraph.domain.AnalysisError;
import com.java.semantic.callgraph.domain.AnalysisMetadata;
import com.java.semantic.callgraph.domain.AnalysisStatus;
import com.java.semantic.callgraph.domain.AnalysisWarning;

import java.util.List;
import java.util.Objects;

public record AnalysisResponse<T>(
        AnalysisStatus status,
        T data,
        List<AnalysisWarning> warnings,
        List<AnalysisError> errors,
        AnalysisMetadata metadata,
        String analyzedRevision) {

    public AnalysisResponse {
        status = Objects.requireNonNull(status, "status is required");
        warnings = List.copyOf(warnings);
        errors = List.copyOf(errors);
        metadata = Objects.requireNonNull(metadata, "metadata is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
    }
}
