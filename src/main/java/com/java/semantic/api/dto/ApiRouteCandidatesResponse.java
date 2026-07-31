package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

public record ApiRouteCandidatesResponse(
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ApiRouteCandidateResponse> candidates,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ApiRouteObservationResponse> observations) {

    public ApiRouteCandidatesResponse {
        candidates = List.copyOf(candidates);
        observations = List.copyOf(observations);
    }
}
