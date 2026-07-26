package com.java.semantic.api.dto;

import com.java.semantic.trie.ApiRouteMatchReason;

import java.util.List;
import java.util.Objects;

public record ApiRouteCandidateResponse(
        String repoId,
        String analyzedRevision,
        String httpMethod,
        String routeTemplate,
        String packageName,
        String className,
        String methodName,
        MethodTargetResolutionResponse analysisTarget,
        List<ApiRouteMatchReason> matchReasons) {

    public ApiRouteCandidateResponse {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        matchReasons = List.copyOf(Objects.requireNonNull(matchReasons, "matchReasons are required"));
    }

}
