package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** bounded context retry collection 的完整計數 response */
public record SourceContextCandidateLimitsResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int candidateLimit,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int returnedCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int totalCount,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean truncated) {
}
