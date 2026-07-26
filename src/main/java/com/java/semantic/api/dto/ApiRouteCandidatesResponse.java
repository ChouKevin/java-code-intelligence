package com.java.semantic.api.dto;

import java.util.List;

public record ApiRouteCandidatesResponse(
        List<ApiRouteCandidateResponse> candidates,
        List<ApiRouteObservationResponse> observations) {

    public ApiRouteCandidatesResponse {
        candidates = List.copyOf(candidates);
        observations = List.copyOf(observations);
    }
}
