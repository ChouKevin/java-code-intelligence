package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.Objects;

/** 一種方法實作探索問題的累計數量 */
public record SemanticImplementationIssueSummaryResponse(@ApiMonitoringField(ApiMonitoringMode.VALUE) String code, @ApiMonitoringField(ApiMonitoringMode.VALUE) int count) {

    public SemanticImplementationIssueSummaryResponse {
        code = Objects.requireNonNull(code, "code is required");
    }
}
