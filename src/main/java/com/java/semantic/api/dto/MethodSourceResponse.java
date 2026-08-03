package com.java.semantic.api.dto;

import com.java.semantic.api.dto.location.SourceRangePayload;
import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 固定 revision 的方法完整宣告位置、source 區段與可直接提交的下一輪操作 */
public record MethodSourceResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceRangePayload declarationLocation,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceSegmentPayload segment,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MethodSourceResponse {
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
