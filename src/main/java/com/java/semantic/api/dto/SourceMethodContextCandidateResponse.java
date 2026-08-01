package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** overloaded method 的 canonical MethodTarget retry */
public record SourceMethodContextCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) DiscoveryFollowUpResponse retry)
        implements SourceContextCandidateResponse {
}
