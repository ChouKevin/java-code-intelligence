package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** canonical source type identity response */
public record SourceTypeSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String fullyQualifiedName,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String sourceFile,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public SourceTypeSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
