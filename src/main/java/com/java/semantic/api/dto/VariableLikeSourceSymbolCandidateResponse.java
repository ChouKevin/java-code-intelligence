package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;

/** field、record component、parameter、local 或 enum constant response */
public record VariableLikeSourceSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String name,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String declarationOwner,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) DeclaredTypeResponse declaredType,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangeResponse representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public VariableLikeSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
