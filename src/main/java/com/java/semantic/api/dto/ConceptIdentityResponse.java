package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Optional;

/** 結構化概念精確 identity 的封閉 HTTP 回應 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConceptIdentityResponse.TypeConceptIdentityResponse.class, name = "TYPE"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.MethodConceptIdentityResponse.class, name = "METHOD"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.FieldConceptIdentityResponse.class, name = "FIELD"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.AnnotationUsageConceptIdentityResponse.class, name = "ANNOTATION_USAGE"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.TypeUsageConceptIdentityResponse.class, name = "TYPE_USAGE"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.ApiRouteConceptIdentityResponse.class, name = "API_ROUTE"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.MqDestinationConceptIdentityResponse.class, name = "MQ_DESTINATION"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.ScheduleConceptIdentityResponse.class, name = "SCHEDULE"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.MapperStatementConceptIdentityResponse.class, name = "MAPPER_STATEMENT"),
        @JsonSubTypes.Type(value = ConceptIdentityResponse.MapperStatementVariantConceptIdentityResponse.class, name = "MAPPER_STATEMENT_VARIANT")
})
public sealed interface ConceptIdentityResponse permits
        ConceptIdentityResponse.TypeConceptIdentityResponse,
        ConceptIdentityResponse.MethodConceptIdentityResponse,
        ConceptIdentityResponse.FieldConceptIdentityResponse,
        ConceptIdentityResponse.AnnotationUsageConceptIdentityResponse,
        ConceptIdentityResponse.TypeUsageConceptIdentityResponse,
        ConceptIdentityResponse.ApiRouteConceptIdentityResponse,
        ConceptIdentityResponse.MqDestinationConceptIdentityResponse,
        ConceptIdentityResponse.ScheduleConceptIdentityResponse,
        ConceptIdentityResponse.MapperStatementConceptIdentityResponse,
        ConceptIdentityResponse.MapperStatementVariantConceptIdentityResponse {

    @JsonAnySetter
    default void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown concept identity property");
    }

    /** 型別宣告 identity 回應 */
    record TypeConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String packageName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String className)
            implements ConceptIdentityResponse {
    }

    /** 方法宣告 identity 回應 */
    record MethodConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target)
            implements ConceptIdentityResponse {
    }

    /** 欄位宣告 identity 回應 */
    record FieldConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String ownerPackageName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String ownerClassName,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String fieldName)
            implements ConceptIdentityResponse {
    }

    /** annotation 使用 identity 回應 */
    record AnnotationUsageConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) DeclarationSubjectResponse declaration,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) AnnotationTypeResponse annotationType)
            implements ConceptIdentityResponse {
    }

    /** 型別使用 identity 回應 */
    record TypeUsageConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) DeclarationSubjectResponse owner,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) TypeUsageLocationResponse location,
            @ApiMonitoringField(ApiMonitoringMode.SIZE) List<TypeUsagePathResponse> path,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) ReferencedTypeResponse referencedType)
            implements ConceptIdentityResponse {
    }

    /** HTTP 路由 entry-point identity 回應 */
    record ApiRouteConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String httpVerb,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String route)
            implements ConceptIdentityResponse {
    }

    /** 訊息目的地 entry-point identity 回應 */
    record MqDestinationConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String broker,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String destination)
            implements ConceptIdentityResponse {
    }

    /** 排程 entry-point identity 回應 */
    record ScheduleConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String triggerKind,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> triggerValue)
            implements ConceptIdentityResponse {
    }

    /** 邏輯 mapper statement identity 回應 */
    record MapperStatementConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MapperStatementKeyResponse statement)
            implements ConceptIdentityResponse {
    }

    /** 實體 mapper statement evidence identity 回應 */
    record MapperStatementVariantConceptIdentityResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MapperStatementVariantResponse variant)
            implements ConceptIdentityResponse {
    }

    /** mapper statement 的 logical key 回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    record MapperStatementKeyResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String namespace,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String statementId) {

        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            throw new IllegalArgumentException("unknown mapper statement key property");
        }
    }

    /** mapper statement 的實體證據回應 */
    @JsonIgnoreProperties(ignoreUnknown = false)
    record MapperStatementVariantResponse(
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MapperStatementKeyResponse statement,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String resourcePath,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @ApiMonitoringField(ApiMonitoringMode.VALUE) Optional<String> databaseId,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) int documentOrdinal,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String representation) {

        @JsonAnySetter
        public void rejectUnknownProperty(String property, Object value) {
            throw new IllegalArgumentException("unknown mapper statement variant property");
        }
    }
}
