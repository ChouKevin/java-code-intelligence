package com.java.semantic.api.dto;
import com.java.semantic.model.codefact.SourceRange;

import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 固定 revision 的方法完整宣告位置、source 區段與可直接提交的下一輪操作 */
public record MethodSourceResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.NESTED) SourceRangePayload declarationLocation,
        @MonitoringField(MonitoringMode.NESTED) SourceSegmentPayload segment,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MethodSourceResponse {
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
