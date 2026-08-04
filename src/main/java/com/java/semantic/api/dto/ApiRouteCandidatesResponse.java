package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;

public record ApiRouteCandidatesResponse(
        @MonitoringField(MonitoringMode.SIZE) List<ApiRouteCandidateResponse> candidates,
        @MonitoringField(MonitoringMode.SIZE) List<ApiRouteObservationResponse> observations) {

    public ApiRouteCandidatesResponse {
        candidates = List.copyOf(candidates);
        observations = List.copyOf(observations);
    }
}
