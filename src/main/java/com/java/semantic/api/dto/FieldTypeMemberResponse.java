package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 帶型別資訊、註解、限制與 follow-up 的 FIELD 成員回應 */
public record FieldTypeMemberResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String kind,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String fieldName,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String writtenType,
        @ApiMonitoringField(ApiMonitoringMode.OMIT) Optional<String> resolvedType,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> annotations,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> limitations,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps) implements TypeMemberResponse {

    public FieldTypeMemberResponse {
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
