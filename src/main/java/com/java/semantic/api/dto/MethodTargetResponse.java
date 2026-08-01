package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** API representation of a source-qualified canonical method target. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MethodTargetResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String packageName,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String className,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String methodName,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> parameterTypes) {

    public MethodTargetResponse {
        parameterTypes = List.copyOf(parameterTypes);
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown method target property");
    }
}
