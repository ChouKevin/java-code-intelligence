package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;

/** analyzed revision 綁定的 closed source-symbol resolution response */
public record SourceSymbolResolutionResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.VALUE) String status,
        @MonitoringField(MonitoringMode.SIZE) List<SourceContextCandidateResponse> contextCandidates,
        @MonitoringField(MonitoringMode.NESTED) BoundedResultResponse contextCandidateLimits,
        @MonitoringField(MonitoringMode.SIZE) List<SourceSymbolCandidateResponse> candidates,
        @MonitoringField(MonitoringMode.SIZE) List<SourceSymbolIssueSummaryResponse> issues) {

    public SourceSymbolResolutionResponse {
        contextCandidates = List.copyOf(contextCandidates);
        candidates = List.copyOf(candidates);
        issues = List.copyOf(issues);
    }
}
