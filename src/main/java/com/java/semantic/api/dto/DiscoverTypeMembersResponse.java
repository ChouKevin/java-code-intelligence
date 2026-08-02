package com.java.semantic.api.dto;

import com.java.semantic.api.monitoring.ApiMonitoringField;
import com.java.semantic.api.monitoring.ApiMonitoringMode;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本與來源型別 identity 的成員探索回應 */
public record DiscoverTypeMembersResponse(
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String repoId,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String analyzedRevision,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
        @ApiMonitoringField(ApiMonitoringMode.VALUE) String typeKind,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> annotations,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> implementedTypes,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<String> extendedTypes,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<TypeMemberResponse> members,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) ConceptPageResponse page,
        @ApiMonitoringField(ApiMonitoringMode.NESTED) ConceptCoverageResponse coverage,
        @ApiMonitoringField(ApiMonitoringMode.SIZE) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public DiscoverTypeMembersResponse {
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        implementedTypes = List.copyOf(Objects.requireNonNull(
                implementedTypes, "implementedTypes are required"));
        extendedTypes = List.copyOf(Objects.requireNonNull(extendedTypes, "extendedTypes are required"));
        members = List.copyOf(Objects.requireNonNull(members, "members are required"));
        page = Objects.requireNonNull(page, "page is required");
        coverage = Objects.requireNonNull(coverage, "coverage is required");
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
