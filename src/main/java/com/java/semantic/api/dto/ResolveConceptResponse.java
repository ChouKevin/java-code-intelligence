package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Objects;

/** 綁定實際分析版本的精確 typed concept resolve 回應 */
public record ResolveConceptResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) ConceptCandidateResponse candidate) {

    public ResolveConceptResponse {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        candidate = Objects.requireNonNull(candidate, "candidate is required");
    }
}
