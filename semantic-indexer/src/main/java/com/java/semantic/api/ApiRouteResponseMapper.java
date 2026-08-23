package com.java.semantic.api;

import com.java.semantic.api.dto.ApiRouteCandidateResponse;
import com.java.semantic.api.dto.ApiRouteCandidatesResponse;
import com.java.semantic.api.dto.ApiRouteObservationResponse;
import com.java.semantic.trie.ApiRouteCandidate;
import com.java.semantic.trie.ApiRouteMatchBatch;
import com.java.semantic.trie.ApiRouteObservation;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ApiRouteResponseMapper {

    private final EntryPointResponseMapper entryPointResponseMapper;

    public ApiRouteResponseMapper(EntryPointResponseMapper entryPointResponseMapper) {
        this.entryPointResponseMapper = Objects.requireNonNull(
                entryPointResponseMapper, "entryPointResponseMapper is required");
    }

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
                entryPointResponseMapper.toResponse(
                        RepositoryId.of(candidate.repoId()),
                        new RepositoryRevision(candidate.analyzedRevision()),
                        candidate.analysisTarget()),
                candidate.matchReasons());
    }

    private ApiRouteObservationResponse toResponse(ApiRouteObservation observation) {
        Objects.requireNonNull(observation, "observation is required");
        return new ApiRouteObservationResponse(observation.code(), observation.description());
    }
}
