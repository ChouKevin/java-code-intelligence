package com.java.semantic.api.dto;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;
import com.java.semantic.api.dto.identity.SourceTypeIdentityPayload;

import java.util.List;
import java.util.Objects;

/** 綁定實際分析版本與來源型別 identity 的成員探索回應 */
public record DiscoverTypeMembersResponse(
        @MonitoringField(MonitoringMode.VALUE) String repoId,
        @MonitoringField(MonitoringMode.VALUE) String analyzedRevision,
        @MonitoringField(MonitoringMode.NESTED) SourceTypeIdentityPayload sourceType,
        @MonitoringField(MonitoringMode.VALUE) String typeKind,
        @MonitoringField(MonitoringMode.SIZE) List<String> annotations,
        @MonitoringField(MonitoringMode.SIZE) List<String> implementedTypes,
        @MonitoringField(MonitoringMode.SIZE) List<String> extendedTypes,
        @MonitoringField(MonitoringMode.SIZE) List<TypeMemberResponse> members,
        @MonitoringField(MonitoringMode.NESTED) PageResponse page,
        @MonitoringField(MonitoringMode.NESTED) ConceptCoverageResponse coverage,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

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
