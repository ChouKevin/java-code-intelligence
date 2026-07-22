package com.java.semantic.api.dto;

import java.util.Objects;

public record ApiRouteCandidateResponse(
        String repoId,
        String analyzedRevision,
        String httpMethod,
        String routeTemplate,
        String packageName,
        String className,
        String methodName,
        MethodTargetResolutionResponse analysisTarget) {

    public ApiRouteCandidateResponse {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
