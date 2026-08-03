package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;

import java.util.List;

/** JDT 證明 compile-time expression 的 static final constant response */
public record StaticConstantSourceSymbolCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.NESTED) SourceMemberIdentityPayload identity,
        @MonitoringField(MonitoringMode.NESTED) DeclaredTypeResponse declaredType,
        @MonitoringField(MonitoringMode.OMIT) String initializerSource,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @MonitoringField(MonitoringMode.VALUE) int occurrenceCount,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public StaticConstantSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
