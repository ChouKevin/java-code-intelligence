package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;

import java.util.List;
import java.util.Objects;

/** field、record component、parameter、local 或 enum constant response */
public record VariableLikeSourceSymbolCandidateResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.NESTED) SourceMemberIdentityPayload identity,
        @MonitoringField(MonitoringMode.NESTED) DeclaredTypeResponse declaredType,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @MonitoringField(MonitoringMode.VALUE) int occurrenceCount,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps)
        implements SourceSymbolCandidateResponse {

    public VariableLikeSourceSymbolCandidateResponse {
        identity = Objects.requireNonNull(identity, "identity is required");
        declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
        if (identity instanceof SourceMemberIdentityPayload.MethodScoped methodScoped
                && !declarationRange.equals(methodScoped.declarationRange())) {
            throw new IllegalArgumentException("method-scoped declaration ranges must match");
        }
        availableFollowUps = List.copyOf(availableFollowUps);
    }
}
