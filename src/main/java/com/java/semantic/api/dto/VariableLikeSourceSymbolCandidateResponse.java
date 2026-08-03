package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;

import java.util.List;
import java.util.Objects;

/** field、record component、parameter、local 或 enum constant response */
public record VariableLikeSourceSymbolCandidateResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceMemberIdentityPayload identity,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) DeclaredTypeResponse declaredType,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload declarationRange,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) TextRangePayload representativeOccurrence,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) int occurrenceCount,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps)
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
