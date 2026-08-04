package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 已 materialize 的 canonical source segment、檔案邊界 context 截斷狀態與可直接提交的 continuation operation */
public record SourceSegmentResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.NESTED) SourceSegmentPayload segment,
        @MonitoringField(MonitoringMode.VALUE) boolean contextTruncated,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

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
