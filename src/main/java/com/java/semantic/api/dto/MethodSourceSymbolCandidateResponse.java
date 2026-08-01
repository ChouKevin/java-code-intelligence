package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** canonical MethodTarget source-symbol response */
public record MethodSourceSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetResponse target,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public MethodSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
