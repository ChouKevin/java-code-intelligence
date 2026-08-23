package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

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
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) String writtenType,
            @MonitoringField(MonitoringMode.VALUE) String simpleTypeName,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @MonitoringField(MonitoringMode.NESTED) Optional<JavaTypeIdentityPayload> resolvedJavaType,
            @MonitoringField(MonitoringMode.VALUE) boolean sourceDefined) implements FieldTypeReferenceResponse {
    }

    /** 參數化型別證據回應 */
    record ParameterizedFieldTypeReferenceResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) String writtenType,
            @MonitoringField(MonitoringMode.NESTED) NamedFieldTypeReferenceResponse rawType,
            @MonitoringField(MonitoringMode.SIZE) List<FieldTypeReferenceResponse> typeArguments)
            implements FieldTypeReferenceResponse {
    }

    /** 基本型別證據回應 */
    record PrimitiveFieldTypeReferenceResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) String writtenType) implements FieldTypeReferenceResponse {
    }

    /** 陣列型別證據回應 */
    record ArrayFieldTypeReferenceResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) String writtenType,
            @MonitoringField(MonitoringMode.NESTED) FieldTypeReferenceResponse elementType,
            @MonitoringField(MonitoringMode.VALUE) int dimensions) implements FieldTypeReferenceResponse {
    }

    /** wildcard 型別證據回應 */
    record WildcardFieldTypeReferenceResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) String writtenType,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @MonitoringField(MonitoringMode.NESTED) Optional<FieldTypeReferenceResponse> upperBound,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @MonitoringField(MonitoringMode.NESTED) Optional<FieldTypeReferenceResponse> lowerBound,
            @MonitoringField(MonitoringMode.VALUE) boolean sourceDefined) implements FieldTypeReferenceResponse {
    }

    /** 型別變數證據回應 */
    record TypeVariableFieldTypeReferenceResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.VALUE) String writtenType,
            @MonitoringField(MonitoringMode.VALUE) String variableName,
            @MonitoringField(MonitoringMode.SIZE) List<FieldTypeReferenceResponse> upperBounds,
            @MonitoringField(MonitoringMode.VALUE) boolean sourceDefined) implements FieldTypeReferenceResponse {
    }
}
