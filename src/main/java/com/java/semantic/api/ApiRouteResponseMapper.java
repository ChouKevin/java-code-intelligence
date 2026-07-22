package com.java.semantic.api;

import com.java.semantic.api.dto.ApiRouteCandidateResponse;
import com.java.semantic.api.dto.ApiRouteCandidatesResponse;
import com.java.semantic.trie.ApiRouteCandidate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public final class ApiRouteResponseMapper {

    public ApiRouteCandidatesResponse toResponse(List<ApiRouteCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates is required");
        return new ApiRouteCandidatesResponse(candidates.stream().map(this::toResponse).toList());
    }

    private ApiRouteCandidateResponse toResponse(ApiRouteCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate is required");
        return new ApiRouteCandidateResponse(
                candidate.repoId(),
                candidate.analyzedRevision(),
                candidate.httpMethod(),
                candidate.routeTemplate(),
                candidate.packageName(),
                candidate.className(),
                candidate.methodName(),
                EntryPointResponseMapper.toResponse(candidate.analysisTarget()));
    }
}
