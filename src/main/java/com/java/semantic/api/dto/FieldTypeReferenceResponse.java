package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Optional;

/** Java 欄位宣告可達型別證據的封閉遞迴 HTTP 回應 */
public sealed interface FieldTypeReferenceResponse permits
        FieldTypeReferenceResponse.NamedFieldTypeReferenceResponse,
        FieldTypeReferenceResponse.ParameterizedFieldTypeReferenceResponse,
        FieldTypeReferenceResponse.PrimitiveFieldTypeReferenceResponse,
        FieldTypeReferenceResponse.ArrayFieldTypeReferenceResponse,
        FieldTypeReferenceResponse.WildcardFieldTypeReferenceResponse,
        FieldTypeReferenceResponse.TypeVariableFieldTypeReferenceResponse {

    /** 名義型別證據回應 */
    record NamedFieldTypeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String simpleTypeName,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<JavaTypeIdentityPayload> resolvedJavaType,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean sourceDefined) implements FieldTypeReferenceResponse {
    }

    /** 參數化型別證據回應 */
    record ParameterizedFieldTypeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) NamedFieldTypeReferenceResponse rawType,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<FieldTypeReferenceResponse> typeArguments)
            implements FieldTypeReferenceResponse {
    }

    /** 基本型別證據回應 */
    record PrimitiveFieldTypeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType) implements FieldTypeReferenceResponse {
    }

    /** 陣列型別證據回應 */
    record ArrayFieldTypeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) FieldTypeReferenceResponse elementType,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int dimensions) implements FieldTypeReferenceResponse {
    }

    /** wildcard 型別證據回應 */
    record WildcardFieldTypeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<FieldTypeReferenceResponse> upperBound,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @ApiMonitoringField(ApiMonitoringMode.NESTED) Optional<FieldTypeReferenceResponse> lowerBound,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean sourceDefined) implements FieldTypeReferenceResponse {
    }

    /** 型別變數證據回應 */
    record TypeVariableFieldTypeReferenceResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String variableName,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<FieldTypeReferenceResponse> upperBounds,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean sourceDefined) implements FieldTypeReferenceResponse {
    }
}
