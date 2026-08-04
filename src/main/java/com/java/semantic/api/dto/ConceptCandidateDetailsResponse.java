package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 概念 identity 以外的封閉 HTTP 細節回應 */
public sealed interface ConceptCandidateDetailsResponse permits
        ConceptCandidateDetailsResponse.FieldDetailsResponse,
        ConceptCandidateDetailsResponse.MapperStatementDetailsResponse {

    /** 欄位宣告型別細節回應 */
    record FieldDetailsResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) FieldTypeReferenceResponse declaredType)
            implements ConceptCandidateDetailsResponse {
    }

    /** mapper statement 解析細節回應 */
    record MapperStatementDetailsResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MapperStatementMappingResponse mapping)
            implements ConceptCandidateDetailsResponse {
    }
}
