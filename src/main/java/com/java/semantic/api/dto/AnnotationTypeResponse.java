package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** annotation 已解析或未解析型別的封閉 HTTP 回應 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "status",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnnotationTypeResponse.ResolvedAnnotationTypeResponse.class, name = "RESOLVED"),
        @JsonSubTypes.Type(value = AnnotationTypeResponse.UnresolvedAnnotationTypeResponse.class, name = "UNRESOLVED")
})
public sealed interface AnnotationTypeResponse permits
        AnnotationTypeResponse.ResolvedAnnotationTypeResponse,
        AnnotationTypeResponse.UnresolvedAnnotationTypeResponse {

    @JsonAnySetter
    default void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown annotation type property");
    }

    /** 已解析 annotation 型別回應 */
    record ResolvedAnnotationTypeResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) JavaTypeIdentityResponse javaType)
            implements AnnotationTypeResponse {
    }

    /** 未解析 annotation 型別回應 */
    record UnresolvedAnnotationTypeResponse(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String status,
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenName)
            implements AnnotationTypeResponse {
    }
}
