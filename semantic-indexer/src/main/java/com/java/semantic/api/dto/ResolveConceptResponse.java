package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.Objects;

/** 綁定實際分析版本的精確 typed concept resolve 回應 */
public record ResolveConceptResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.NESTED) ConceptCandidateResponse candidate) {

    public ResolveConceptResponse {
        repoId = Objects.requireNonNull(repoId, "repoId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        candidate = Objects.requireNonNull(candidate, "candidate is required");
    }
}
