package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 方法實作探索完整性與保留問題的彙總 */
public record MethodImplementationResolutionResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) Status status,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<SemanticImplementationIssueSummaryResponse> issueSummaries) {

    public MethodImplementationResolutionResponse {
        status = Objects.requireNonNull(status, "status is required");
        issueSummaries = List.copyOf(Objects.requireNonNull(issueSummaries, "issueSummaries are required"));
    }

    /** 方法實作探索是否遺失本地可用候選 */
    public enum Status {
        COMPLETE,
        PARTIAL
    }
}
