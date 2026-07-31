package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** 無可執行 API 時僅提供原因與建議動作的後續指引 */
public record UnavailableDiscoveryFollowUpResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String reason,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) String recommendedAction) {
}
