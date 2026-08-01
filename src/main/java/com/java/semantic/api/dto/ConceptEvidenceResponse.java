package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 僅由 typed identity 衍生且不包含來源內容的封閉 evidence 回應 */
public record ConceptEvidenceResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) ConceptIdentityResponse identity) {
}
