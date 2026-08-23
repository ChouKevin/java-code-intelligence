package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

/** 無可執行 API 時僅提供原因與建議動作的後續指引 */
public record UnavailableDiscoveryFollowUpResponse(
        @MonitoringField(MonitoringMode.VALUE) String reason,
        @MonitoringField(MonitoringMode.SIZE) String recommendedAction) {
}
