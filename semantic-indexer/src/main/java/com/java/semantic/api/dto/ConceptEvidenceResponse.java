package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 僅由 typed identity 衍生且不包含來源內容的封閉 evidence 回應 */
public record ConceptEvidenceResponse(
        @MonitoringField(MonitoringMode.NESTED) ConceptIdentityResponse identity) {
}
