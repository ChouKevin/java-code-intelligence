package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import java.util.List;

/** 以 canonical 來源型別 identity 識別的來源型別符號回應 */
public record SourceTypeSymbolCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload identity,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @MonitoringField(MonitoringMode.VALUE) int occurrenceCount,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public SourceTypeSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
