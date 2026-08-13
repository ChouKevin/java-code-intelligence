package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.SourceMemberIdentityPayload;
import com.java.semantic.api.dto.location.TextRangePayload;
import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 帶型別、來源宣告與 follow-up 的 RECORD_COMPONENT 成員回應 */
public record RecordComponentTypeMemberResponse(
        @MonitoringField(MonitoringMode.VALUE) String kind,
        @MonitoringField(MonitoringMode.NESTED) SourceMemberIdentityPayload identity,
        @MonitoringField(MonitoringMode.VALUE) String writtenType,
        @MonitoringField(MonitoringMode.OMIT) Optional<String> resolvedType,
        @MonitoringField(MonitoringMode.NESTED) TextRangePayload declarationRange,
        @MonitoringField(MonitoringMode.SIZE) List<String> annotations,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) implements TypeMemberResponse {

    public RecordComponentTypeMemberResponse {
        identity = Objects.requireNonNull(identity, "identity is required");
        resolvedType = Objects.requireNonNull(resolvedType, "resolvedType is required");
        declarationRange = Objects.requireNonNull(declarationRange, "declarationRange is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(availableFollowUps, "availableFollowUps are required"));
    }
}
