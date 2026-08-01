package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 概念 identity 以外的封閉 HTTP 細節回應 */
public sealed interface ConceptCandidateDetailsResponse permits
        ConceptCandidateDetailsResponse.FieldDetailsResponse,
        ConceptCandidateDetailsResponse.MapperStatementDetailsResponse {

    /** 欄位宣告型別細節回應 */
    record FieldDetailsResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) FieldTypeReferenceResponse declaredType)
            implements ConceptCandidateDetailsResponse {
    }

    /** mapper statement 解析細節回應 */
    record MapperStatementDetailsResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MapperStatementMappingResponse mapping)
            implements ConceptCandidateDetailsResponse {
    }
}
