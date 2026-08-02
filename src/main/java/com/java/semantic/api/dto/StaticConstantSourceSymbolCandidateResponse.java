package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;

import java.util.List;

/** JDT 證明 compile-time expression 的 static final constant response */
public record StaticConstantSourceSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceMemberIdentityPayload identity,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) DeclaredTypeResponse declaredType,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) String initializerSource,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public StaticConstantSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
