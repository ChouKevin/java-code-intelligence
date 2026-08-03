package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;

import java.util.List;

/** 以 canonical 方法目標識別的方法來源符號回應 */
public record MethodSourceSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public MethodSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
