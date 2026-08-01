package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 型別使用 identity 的已解析型別與陣列維度 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReferencedTypeResponse(
        @ApiMonitoringField(ApiMonitoringMode.NESTED) JavaTypeIdentityResponse javaType,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int arrayDimensions) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown referenced type property");
    }
}
