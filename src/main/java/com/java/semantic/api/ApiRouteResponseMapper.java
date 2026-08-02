package com.java.semantic.api;

import com.java.semantic.api.dto.ApiRouteCandidateResponse;
import com.java.semantic.api.dto.ApiRouteCandidatesResponse;
import com.java.semantic.api.dto.ApiRouteObservationResponse;
import com.java.semantic.trie.ApiRouteCandidate;
import com.java.semantic.trie.ApiRouteMatchBatch;
import com.java.semantic.trie.ApiRouteObservation;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ApiRouteResponseMapper {

    public ApiRouteCandidatesResponse toResponse(ApiRouteMatchBatch batch) {
        Objects.requireNonNull(batch, "batch is required");
        return new ApiRouteCandidatesResponse(
                batch.matches().stream().map(ApiRouteCandidate::from).map(this::toResponse).toList(),
                batch.observations().stream().map(this::toResponse).toList());
    }

    private ApiRouteCandidateResponse toResponse(ApiRouteCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate is required");
        return new ApiRouteCandidateResponse(
                candidate.repoId(),
                candidate.analyzedRevision(),
                candidate.httpMethod(),
                candidate.routeTemplate(),
                JavaSourceIdentityHttpMapper.toPayload(candidate.sourceType()),
                candidate.methodName(),
                EntryPointResponseMapper.toResponse(candidate.analysisTarget()),
                candidate.matchReasons());
    }

    private ApiRouteObservationResponse toResponse(ApiRouteObservation observation) {
        Objects.requireNonNull(observation, "observation is required");
        return new ApiRouteObservationResponse(observation.code(), observation.description());
    }
}
