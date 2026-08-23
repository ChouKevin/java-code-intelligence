package com.java.semantic.api.dto;

import com.java.semantic.api.dto.identity.MethodTargetPayload;

import com.java.semantic.monitoring.MonitoringField;
import com.java.semantic.monitoring.MonitoringMode;

import java.util.List;
import java.util.Objects;

/** 可作為後續外呼圖輸入的方法實作候選 */
public record MethodImplementationCandidateResponse(
        @MonitoringField(MonitoringMode.NESTED) MethodTargetPayload target,
        @MonitoringField(MonitoringMode.VALUE) boolean primary,
        @MonitoringField(MonitoringMode.SIZE) List<String> qualifiers,
        @MonitoringField(MonitoringMode.SIZE) List<String> profiles,
        @MonitoringField(MonitoringMode.NESTED) List<DiscoveryFollowUpResponse> availableFollowUps) {

    public MethodImplementationCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "qualifiers are required"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles are required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
    }
}
