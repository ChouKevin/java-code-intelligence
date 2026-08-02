package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import com.java.semantic.trie.ApiRouteMatchReason;

import java.util.List;
import java.util.Objects;

public record ApiRouteCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String httpMethod,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String routeTemplate,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String methodName,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResolutionResponse analysisTarget,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<ApiRouteMatchReason> matchReasons) {

    public ApiRouteCandidateResponse {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        matchReasons = List.copyOf(Objects.requireNonNull(matchReasons, "matchReasons are required"));
    }

}
