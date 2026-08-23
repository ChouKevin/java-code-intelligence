package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;

import java.util.List;

/** 以 canonical 方法目標識別的方法來源符號回應 */
public record MethodSourceSymbolCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @MonitoringField(MonitoringMode.VALUE) int occurrenceCount,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public MethodSourceSymbolCandidateResponse {
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
