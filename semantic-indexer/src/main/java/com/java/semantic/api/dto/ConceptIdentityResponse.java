package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.MapperStatementIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementKeyPayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

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
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType)
            implements ConceptIdentityResponse {
    }

    /** 方法宣告 identity 回應 */
    record MethodConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target)
            implements ConceptIdentityResponse {
    }

    /** 欄位宣告 identity 回應 */
    record FieldConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) SourceMemberIdentityPayload identity)
            implements ConceptIdentityResponse {
    }

    /** annotation 使用 identity 回應 */
    record AnnotationUsageConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) DeclarationSubjectResponse declaration,
            @MonitoringField(MonitoringMode.NESTED) AnnotationTypeResponse annotationType)
            implements ConceptIdentityResponse {
    }

    /** 型別使用 identity 回應 */
    record TypeUsageConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) DeclarationSubjectResponse owner,
            @MonitoringField(MonitoringMode.NESTED) TypeUsageLocationResponse location,
            @MonitoringField(MonitoringMode.SIZE) List<TypeUsagePathResponse> path,
            @MonitoringField(MonitoringMode.NESTED) ReferencedTypeResponse referencedType)
            implements ConceptIdentityResponse {
    }

    /** HTTP 路由 entry-point identity 回應 */
    record ApiRouteConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
            @MonitoringField(MonitoringMode.VALUE) String httpVerb,
            @MonitoringField(MonitoringMode.VALUE) String route)
            implements ConceptIdentityResponse {
    }

    /** 訊息目的地 entry-point identity 回應 */
    record MqDestinationConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
            @MonitoringField(MonitoringMode.VALUE) String broker,
            @MonitoringField(MonitoringMode.VALUE) String destination)
            implements ConceptIdentityResponse {
    }

    /** 排程 entry-point identity 回應 */
    record ScheduleConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
            @MonitoringField(MonitoringMode.VALUE) String triggerKind,
            @JsonInclude(JsonInclude.Include.NON_ABSENT)
            @MonitoringField(MonitoringMode.VALUE) Optional<String> triggerValue)
            implements ConceptIdentityResponse {
    }

    /** 邏輯 mapper statement identity 回應 */
    record MapperStatementConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MapperStatementKeyPayload identity)
            implements ConceptIdentityResponse {
    }

    /** 實體 mapper statement evidence identity 回應 */
    record MapperStatementVariantConceptIdentityResponse(
            @MonitoringField(MonitoringMode.VALUE) String kind,
            @MonitoringField(MonitoringMode.NESTED) MapperStatementIdentityPayload identity)
            implements ConceptIdentityResponse {
    }
}
