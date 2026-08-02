package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.java.semantic.api.dto.identity.MethodTargetPayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** repository-local reference 所屬的封閉 METHOD 或 TYPE context */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = InternalSourceReferenceContextPayload.Type.class, name = "TYPE"),
        @JsonSubTypes.Type(value = InternalSourceReferenceContextPayload.Method.class, name = "METHOD")
})
public sealed interface InternalSourceReferenceContextPayload permits
        InternalSourceReferenceContextPayload.Type,
        InternalSourceReferenceContextPayload.Method {

    /** TYPE context */
    record Type(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload sourceType)
            implements InternalSourceReferenceContextPayload {
    }

    /** METHOD context */
    record Method(
            @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
            @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload method)
            implements InternalSourceReferenceContextPayload {
    }
}
