package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import java.util.List;

/** 以 canonical 來源型別 identity 識別的來源型別符號回應 */
public record SourceTypeSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload identity,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public SourceTypeSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
