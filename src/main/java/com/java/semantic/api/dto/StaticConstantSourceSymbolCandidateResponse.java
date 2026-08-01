package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** JDT 證明 compile-time expression 的 static final constant response */
public record StaticConstantSourceSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String declarationOwner,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) DeclaredTypeResponse declaredType,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String initializerSource,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public StaticConstantSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
