package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;

/** 帶完整 canonical target 與分析 follow-up 的 METHOD 成員回應 */
public record MethodTypeMemberResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) MethodTargetPayload target,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) implements TypeMemberResponse {

    public MethodTypeMemberResponse {
        target = Objects.requireNonNull(target, "target is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
