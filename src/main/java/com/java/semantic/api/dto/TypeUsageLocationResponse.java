package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 型別使用宣告槽位的 HTTP 回應 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TypeUsageLocationResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String slot,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int index) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown type usage location property");
    }
}
