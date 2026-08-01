package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** analyzed revision 綁定的 closed source-symbol resolution response */
public record SourceSymbolResolutionResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<SourceContextCandidateResponse> contextCandidates,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceContextCandidateLimitsResponse contextCandidateLimits,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<SourceSymbolCandidateResponse> candidates,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<SourceSymbolIssueSummaryResponse> issues) {

    public SourceSymbolResolutionResponse {
        contextCandidates = List.copyOf(contextCandidates);
        candidates = List.copyOf(candidates);
        issues = List.copyOf(issues);
    }
}
