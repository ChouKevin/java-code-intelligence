package com.java.semantic.api.dto;

import java.util.List;

public record ApiRouteCandidatesResponse(List<ApiRouteCandidateResponse> candidates) {

    public ApiRouteCandidatesResponse {
        candidates = List.copyOf(candidates);
    }
}
