package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本的方法實作探索回應 */
public record DiscoverMethodImplementationsResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String revision,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload requestedTarget,
        @MonitoringField(MonitoringMode.SIZE) List<MethodImplementationCandidateResponse> candidates,
        @MonitoringField(MonitoringMode.NESTED) BoundedResultResponse limits,
        @MonitoringField(MonitoringMode.NESTED) MethodImplementationResolutionResponse resolution) {

    public DiscoverMethodImplementationsResponse {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        requestedTarget = Objects.requireNonNull(requestedTarget, "requestedTarget is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        limits = Objects.requireNonNull(limits, "limits are required");
        resolution = Objects.requireNonNull(resolution, "resolution is required");
    }
}
