package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import com.java.semantic.trie.ApiRouteMatchReason;

import java.util.List;
import java.util.Objects;

public record ApiRouteCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.VALUE) String httpMethod,
        @MonitoringField(MonitoringMode.VALUE) String routeTemplate,
        @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
        @MonitoringField(MonitoringMode.VALUE) String methodName,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget,
        @MonitoringField(MonitoringMode.SIZE) List<ApiRouteMatchReason> matchReasons) {

    public ApiRouteCandidateResponse {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        matchReasons = List.copyOf(Objects.requireNonNull(matchReasons, "matchReasons are required"));
    }

}
