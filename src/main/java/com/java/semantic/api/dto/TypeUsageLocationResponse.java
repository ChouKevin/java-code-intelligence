package com.java.semantic.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 型別使用宣告槽位的 HTTP 回應 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TypeUsageLocationResponse(
        @MonitoringField(MonitoringMode.VALUE) String slot,
        @MonitoringField(MonitoringMode.VALUE) int index) {

    @JsonAnySetter
    public void rejectUnknownProperty(String property, Object value) {
        throw new IllegalArgumentException("unknown type usage location property");
    }
}
