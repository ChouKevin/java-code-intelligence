package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 已 materialize 的 canonical source segment、檔案邊界 context 截斷狀態與可直接提交的 continuation operation */
public record SourceSegmentResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceSegmentPayload segment,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) boolean contextTruncated,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public SourceSegmentResponse {
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }

    public SourceSegmentResponse(
            String repoId,
            String analyzedRevision,
            SourceSegmentPayload segment,
            boolean contextTruncated) {
        this(repoId, analyzedRevision, segment, contextTruncated, List.of());
    }
}
