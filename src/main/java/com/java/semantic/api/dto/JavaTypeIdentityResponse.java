package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** HTTP 回應中的已解析 Java 型別座標 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record JavaTypeIdentityResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String packageName,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String className) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown Java type identity property");
    }
}
