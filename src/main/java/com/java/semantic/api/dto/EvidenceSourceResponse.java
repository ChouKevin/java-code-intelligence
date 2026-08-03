package com.java.semantic.api.dto;

import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** typed evidence 的 canonical location 與 bounded source payload */
public record EvidenceSourceResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.NESTED) EvidenceSourceIdentityPayload identity,
        @MonitoringField(MonitoringMode.NESTED) SourceRangePayload location,
        @MonitoringField(MonitoringMode.NESTED) SourceSegmentPayload segment,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public EvidenceSourceResponse {
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
