package com.java.semantic.api;

import com.java.semantic.api.dto.AnalysisResponse;
import com.java.semantic.callgraph.domain.RevisionBoundAnalysisResult;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class AnalysisResponseMapper {

    public <T> AnalysisResponse<T> toResponse(RevisionBoundAnalysisResult<T> result) {
        Objects.requireNonNull(result, "result is required");
        return new AnalysisResponse<>(
                result.status(),
                result.data(),
                result.warnings(),
                result.errors(),
                result.metadata(),
                result.analyzedRevision());
    }
}
