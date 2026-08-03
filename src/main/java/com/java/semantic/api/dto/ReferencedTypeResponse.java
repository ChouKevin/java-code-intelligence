package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.JavaTypeIdentityPayload;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 型別使用 identity 的已解析型別與陣列維度 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReferencedTypeResponse(
        @MonitoringField(MonitoringMode.NESTED) JavaTypeIdentityPayload javaType,
        @MonitoringField(MonitoringMode.VALUE) int arrayDimensions) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown referenced type property");
    }
}
