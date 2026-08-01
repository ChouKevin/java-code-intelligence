package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

/** duplicate source type 的 exact file retry */
public record SourceTypeContextCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) DiscoveryFollowUpResponse retry)
        implements SourceContextCandidateResponse {
}
