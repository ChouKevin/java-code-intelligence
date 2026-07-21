package com.java.semantic.api.dto;

public record ApiRouteCandidateResponse(
        String repoId,
        String analyzedRevision,
        String httpMethod,
        String routeTemplate,
        String packageName,
        String className,
        String methodName) {
}
