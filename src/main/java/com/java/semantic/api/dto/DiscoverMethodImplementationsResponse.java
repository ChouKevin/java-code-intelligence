package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本的方法實作探索回應 */
public record DiscoverMethodImplementationsResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String revision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse requestedTarget,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<MethodImplementationCandidateResponse> candidates,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodImplementationLimitsResponse limits,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodImplementationResolutionResponse resolution) {

    public DiscoverMethodImplementationsResponse {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        requestedTarget = Objects.requireNonNull(requestedTarget, "requestedTarget is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        limits = Objects.requireNonNull(limits, "limits are required");
        resolution = Objects.requireNonNull(resolution, "resolution is required");
    }
}
