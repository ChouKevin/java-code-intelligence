package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.Objects;

/** 一種方法實作探索問題的累計數量 */
public record SemanticImplementationIssueSummaryResponse(@MonitoringField(MonitoringMode.VALUE) String code, @MonitoringField(MonitoringMode.VALUE) int count) {

    public SemanticImplementationIssueSummaryResponse {
        code = Objects.requireNonNull(code, "code is required");
    }
}
