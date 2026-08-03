package com.java.semantic.api.dto;

import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** typed evidence 的 canonical location 與 bounded source payload */
public record EvidenceSourceResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) EvidenceSourceIdentityPayload identity,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangePayload location,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceSegmentPayload segment,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public EvidenceSourceResponse {
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
